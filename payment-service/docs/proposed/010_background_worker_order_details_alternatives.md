# Architecture Review: Order & Payment Async Status Sync - Critical Analysis & Alternatives

## Executive Summary

This document provides a senior architect-level review of the proposed Event-Driven Async Synchronization model (documented in `007_background_worker_order_details_api.md`). It identifies critical issues, edge cases, and failure scenarios, followed by robust alternatives and a recommended implementation approach.

---

## Critical Problems Identified

### 1. Race Condition: Webhook vs Worker Collision

**Problem**: The background worker fetches status from the vendor API while a webhook might arrive simultaneously, causing data corruption.

**Failure Scenario**:
```
T0: Worker fetches vendor API → returns "PENDING"
T1: Webhook arrives with "SUCCESS" → writes to DB
T2: Worker writes "PENDING" to DB → OVERWRITES success status!
```

**Impact**: Payment marked as pending when it actually succeeded. Customer is charged, but order shows as failed. This is a **critical payment reconciliation issue**.

**Root Cause**: No coordination mechanism between webhook handler and background worker. Last-write-wins strategy with no state transition validation causes data loss.

**Solution - State Management + Optimistic Locking**: 

The key insight: **Not all state transitions are valid**. Terminal states (SUCCESS, FAILED) cannot transition back to non-terminal states (PENDING, PROCESSING).

**Implementation Strategy**:

1. **Optimistic Locking** - Prevents lost updates (concurrent modification detection)
2. **State Machine Validation** - Prevents invalid state transitions (business logic enforcement)
3. **Rule**: "First write wins" for terminal states; later writes succeed only if transition is valid

**Valid State Transitions**:
```
PENDING → PROCESSING  ✅ (progression)
PENDING → SUCCESS     ✅ (completion)
PENDING → FAILED      ✅ (completion)
PROCESSING → SUCCESS  ✅ (completion)
PROCESSING → FAILED   ✅ (completion)
PENDING → PENDING     ✅ (idempotent, update metadata/timestamp)

SUCCESS → *           ❌ (terminal state, immutable)
FAILED → *            ❌ (terminal state, immutable)
PROCESSING → PENDING  ❌ (regression, invalid)
```

**Code Implementation**:

```java
@Entity
public class PaymentAttempt {
    @Id
    private UUID id;
    
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;
    
    @Version
    private Integer version; // Optimistic locking
    
    private LocalDateTime updatedAt;
    private String updatedBy; // "WEBHOOK" or "POLL" for audit
    
    /**
     * Validates if a state transition is allowed.
     * Terminal states cannot transition to any other state.
     */
    public boolean canTransitionTo(PaymentStatus newStatus) {
        // Terminal states are immutable
        if (this.status.isTerminal()) {
            return this.status == newStatus; // Only allow idempotent updates
        }
        
        // Non-terminal states can transition to any state (progression allowed)
        return true;
    }
}

@Getter
@AllArgsConstructor
public enum PaymentStatus {
    PENDING(false),
    PROCESSING(false),
    SUCCESS(true),
    FAILED(true);
    
    private final boolean terminal;
}

// Repository with state-aware update
@Repository
public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {
    
    /**
     * Updates payment status with optimistic locking AND state validation.
     * Returns number of rows updated (0 if version conflict or invalid transition).
     */
    @Modifying
    @Query("""
        UPDATE PaymentAttempt pa
        SET pa.status = :newStatus,
            pa.version = pa.version + 1,
            pa.updatedAt = :updatedAt,
            pa.updatedBy = :updatedBy
        WHERE pa.id = :id
          AND pa.version = :expectedVersion
          AND (
            -- Allow if current state is non-terminal
            pa.status IN ('PENDING', 'PROCESSING')
            OR
            -- Allow idempotent updates (same status)
            pa.status = :newStatus
          )
        """)
    int updateStatusWithValidation(
        @Param("id") UUID id,
        @Param("newStatus") PaymentStatus newStatus,
        @Param("expectedVersion") Integer expectedVersion,
        @Param("updatedAt") LocalDateTime updatedAt,
        @Param("updatedBy") String updatedBy
    );
}

// Sync Worker with state validation
@Service
public class PaymentSyncWorker {
    
    public void syncPayment(UUID attemptId) {
        // 1. Load current state
        PaymentAttempt attempt = paymentRepo.findById(attemptId).orElseThrow();
        
        // 2. Fetch vendor status
        VendorStatusResponse vendorStatus = vendorClient.getStatus(attempt.getVendorRef());
        PaymentStatus newStatus = vendorStatus.getStatus();
        
        // 3. Check if transition is valid (pre-flight check for better logging)
        if (!attempt.canTransitionTo(newStatus)) {
            log.info("Skipping invalid state transition: {} → {} (terminal state reached)",
                attempt.getStatus(), newStatus);
            metricsRegistry.counter("payment.sync.skipped", 
                "reason", "terminal_state").increment();
            return;
        }
        
        // 4. Attempt update with optimistic lock + state validation
        int updatedRows = paymentRepo.updateStatusWithValidation(
            attemptId,
            newStatus,
            attempt.getVersion(),
            LocalDateTime.now(),
            "POLL"
        );
        
        if (updatedRows == 0) {
            // Either version conflict OR invalid state transition
            // Reload to determine which
            PaymentAttempt current = paymentRepo.findById(attemptId).orElseThrow();
            
            if (!current.getVersion().equals(attempt.getVersion())) {
                log.info("Payment {} updated concurrently (version {} → {}), likely webhook",
                    attemptId, attempt.getVersion(), current.getVersion());
                metricsRegistry.counter("payment.sync.conflict", 
                    "reason", "version").increment();
            } else {
                log.info("Invalid state transition prevented: {} → {} (terminal state)",
                    current.getStatus(), newStatus);
                metricsRegistry.counter("payment.sync.conflict", 
                    "reason", "invalid_transition").increment();
            }
            return;
        }
        
        log.info("Payment status updated: {} → {}", attempt.getStatus(), newStatus);
        metricsRegistry.counter("payment.sync.success").increment();
    }
}

// Webhook Handler with same validation
@Service
public class WebhookHandler {
    
    public void handlePaymentWebhook(WebhookEvent event) {
        UUID attemptId = event.getPaymentAttemptId();
        PaymentStatus newStatus = event.getStatus();
        
        PaymentAttempt attempt = paymentRepo.findById(attemptId).orElseThrow();
        
        // State validation
        if (!attempt.canTransitionTo(newStatus)) {
            log.warn("Webhook attempted invalid transition: {} → {}, ignoring",
                attempt.getStatus(), newStatus);
            metricsRegistry.counter("webhook.invalid_transition").increment();
            return;
        }
        
        // Optimistic lock update
        int updated = paymentRepo.updateStatusWithValidation(
            attemptId,
            newStatus,
            attempt.getVersion(),
            LocalDateTime.now(),
            "WEBHOOK"
        );
        
        if (updated == 0) {
            log.warn("Webhook version conflict for payment {}", attemptId);
            metricsRegistry.counter("webhook.conflict").increment();
            // Could retry or just log (webhook will be replayed by vendor)
        }
    }
}
```

**Race Condition Resolution**:

```
Scenario 1: Webhook arrives first (common case)
T0: Worker fetches vendor → "PENDING"
T1: Webhook arrives → writes "SUCCESS" (version 1 → 2)
T2: Worker attempts write "PENDING" → REJECTED (version mismatch + invalid transition)
Result: ✅ SUCCESS persists (correct)

Scenario 2: Worker completes first
T0: Worker fetches vendor → "SUCCESS"
T1: Worker writes "SUCCESS" (version 1 → 2)
T2: Webhook arrives (delayed) → attempts "SUCCESS" (version 1)
T3: Update REJECTED (version mismatch), but idempotent so safe
Result: ✅ SUCCESS persists (correct)

Scenario 3: Webhook with stale PENDING
T0: Worker fetches vendor → "SUCCESS"
T1: Worker writes "SUCCESS" (version 1 → 2, terminal)
T2: Stale webhook arrives → attempts "PENDING" (version 2)
T3: Update REJECTED (terminal state validation)
Result: ✅ SUCCESS persists (correct, invalid transition blocked)

Scenario 4: Both update to same terminal state (idempotent)
T0: Worker writes "FAILED" (version 1 → 2)
T1: Webhook writes "FAILED" (version 2 → 3, idempotent)
Result: ✅ FAILED persists with latest metadata (safe idempotent update)
```

**Additional Safeguards**:

1. **Audit Trail**: Track `updatedBy` field to distinguish webhook vs poll updates for debugging
2. **Metrics**: Separate counters for version conflicts vs invalid transitions
3. **Alerts**: Alert on unexpected invalid transitions (may indicate vendor API issues)
4. **Reconciliation**: Daily job to compare DB state with vendor API for terminal payments

**Why This Approach Works**:

- ✅ **Optimistic locking** prevents concurrent modification data loss
- ✅ **State machine validation** prevents logical inconsistencies
- ✅ **Terminal state immutability** ensures SUCCESS/FAILED cannot be corrupted
- ✅ **Idempotency** allows duplicate updates without corruption
- ✅ **Source tracking** enables audit and debugging
- ✅ **No distributed locks needed** for correctness (DB ACID guarantees sufficient)

---

### 2. Thundering Herd Not Actually Solved

**Problem**: The proposed solution dispatches async workers before checking if a lock exists, causing database contention.

**Failure Scenario**:
```
100 concurrent GET requests → All trigger @Async worker 
→ 100 threads attempt INSERT into lock table 
→ 99 fail with constraint violation 
→ Master DB connection pool exhaustion
```

**Impact**:
- Master database write hotspot on lock table
- Application thread pool exhaustion
- Connection pool starvation affecting other operations
- Increased P99 latency across all endpoints

**Better Approach**:
```java
// Check lock BEFORE dispatching worker
Optional<PaymentSyncLock> existingLock = lockRepository.findById(paymentId);
if (existingLock.isEmpty() || existingLock.get().isExpired()) {
    // Only dispatch if no active lock
    asyncExecutor.syncPayment(paymentId);
}
```

**Additional Safeguard**: Rate-limit sync attempts per payment using `last_synced_at` timestamp:
```java
if (lastSyncedAt == null || lastSyncedAt.isBefore(now().minus(2, MINUTES))) {
    // Proceed with sync attempt
}
```

---

### 3. Lock Granularity Mismatch

**Problem**: The lock table uses `payment_id` as primary key, but the document refers to `PaymentAttempts` (plural). Ambiguity in lock scope.

**Questions**:
- Is locking per Payment or per PaymentAttempt?
- If one payment has multiple attempts, does the lock prevent concurrent syncs of different attempts?

**Impact**: Potential race conditions if multiple attempts of the same payment are synced concurrently.

**Recommendation**: Use `payment_attempt_id` as the primary key if attempt-level locking is required. Clarify the relationship between Payment and PaymentAttempt in the domain model.

---

### 4. "One Sync" is Insufficient for Long-Running Payments

**Problem**: The design only syncs once when a GET request is made. Payments can stay in PENDING state for extended periods (minutes to hours) due to:
- Bank processing delays
- 3D Secure authentication flows
- Manual review processes
- Network retries at vendor side

**Gap**: If a payment is still pending after the sync, it never updates until another user initiates a GET request.

**Failure Scenario**:
```
T0: User calls GET → Worker syncs → Still PENDING
T1: Payment completes at vendor (no webhook due to network issue)
T2-T∞: Payment stuck in PENDING forever (zombie payment)
```

**Impact**: Stale payment data, poor user experience, manual reconciliation required.

**Solution**: Implement a **scheduled background job** that polls non-terminal payments:
```java
@Scheduled(fixedDelay = 600000) // Every 10 minutes
public void syncStalePendingPayments() {
    List<PaymentAttempt> stalePayments = repo.findByStatusInAndLastSyncedAtBefore(
        List.of(PENDING, PROCESSING),
        now().minus(10, MINUTES)
    );
    
    stalePayments.forEach(attempt -> asyncExecutor.syncPayment(attempt.getId()));
}
```

**Enhancement**: Use exponential backoff - poll frequently in early stages, then slow down:
- First 10 minutes: poll every 2 minutes
- 10-60 minutes: poll every 10 minutes
- After 1 hour: poll every 30 minutes
- After 24 hours: stop polling (mark for manual review)

---

### 5. Stale Lock Cleanup is Inadequate

**Problem**: The proposed TTL solution (`locked_at < NOW() - 5 MINUTES`) overwrites locks older than 5 minutes, but this can cause issues:

**Scenario**: A legitimate sync takes 6 minutes due to vendor API slowness or network issues.

**Risk**: Two workers now process the same payment simultaneously, causing duplicate vendor API calls and potential double-updates.

**Better Solutions**:

**Option A: Lock Ownership + Heartbeat**
```java
Table: payment_sync_locks
- payment_id (UUID - PK)
- lock_owner_id (String - worker/pod identifier)
- locked_at (Timestamp)
- heartbeat_at (Timestamp)

Worker pseudocode:
1. INSERT lock with owner_id
2. Every 30s: UPDATE heartbeat_at WHERE lock_owner_id = self
3. On completion: DELETE lock WHERE lock_owner_id = self

Cleanup job:
DELETE FROM payment_sync_locks 
WHERE heartbeat_at < NOW() - 2 MINUTES 
  AND lock_owner_id != current_worker_id
```

**Option B: Redis with TTL** (Preferred for distributed systems)
```java
boolean lockAcquired = redisTemplate.opsForValue()
    .setIfAbsent(
        "payment_lock:" + paymentId,
        workerId,
        Duration.ofMinutes(5)
    );

// Redis automatically expires lock after TTL
// Worker must complete within TTL or extend it
```

---
Questions: How is the heardbeat updated by the worker at every 30 seconds? can you write some psuedocode to show that like how it will be implemented in the code while processing a request

----

### 6. Read Replica Strategy Breaks CQRS Promise

**Problem**: The document claims this enables CQRS with read replicas, but the worker writes to master **during GET request processing**. Replication lag undermines this:

**Failure Scenario**:
```
T0: User → GET request hits replica → returns stale "PENDING"
T1: Worker writes "SUCCESS" to master
T2: Replication lag 500ms
T3: User → GET request hits same replica → STILL returns "PENDING"!
```

**Impact**: Users see stale data immediately after a sync, defeating the purpose.

**True CQRS Approach**:
- GET endpoint **only reads** from replica, never triggers writes
- Separate polling service constantly syncs non-terminal payments
- Read model (replica) is eventually consistent by design
- If real-time data is required, provide explicit "Refresh" action that reads from master

**Alternative**: Cache-Aside Pattern
```
GET endpoint:
1. Check Redis cache (TTL: 30s)
2. If miss: read from replica + cache result
3. Async trigger sync (fire-and-forget)
4. Return cached/replica data

Worker:
1. Sync with vendor
2. Update master DB
3. Invalidate cache
4. Next GET gets fresh data from cache or replica
```

---

### 7. No Idempotency or Rate Limiting

**Problem**: Payment vendors rate-limit status check APIs (typically 10-100 req/sec). Missing controls:

**Failure Scenarios**:

**Scenario A: Rate Limit Ban**
```
High-traffic site with 1000 concurrent pending payments
→ 1000 GET requests in 1 second
→ 1000 vendor API calls
→ Vendor bans your API key for 24 hours
→ ALL payment processing stops
```

**Scenario B: Redundant Polling**
```
User repeatedly refreshes order page (10 requests in 10 seconds)
→ 10 sync attempts for same payment
→ Wastes vendor API quota
→ Increases costs (some vendors charge per API call)
```

**Required Safeguards**:

**A. Minimum Sync Interval**
```java
// Don't sync if recently synced
if (paymentAttempt.getLastSyncedAt() != null && 
    paymentAttempt.getLastSyncedAt().isAfter(now().minus(2, MINUTES))) {
    return; // Skip sync
}
```

**B. Vendor API Client with Retry + Circuit Breaker**
```java
@CircuitBreaker(name = "paymentVendor", fallbackMethod = "syncFallback")
@RateLimiter(name = "paymentVendor")
@Retry(name = "paymentVendor")
public VendorStatus checkPaymentStatus(String vendorRef) {
    return vendorClient.getStatus(vendorRef);
}
```

**C. Application-Level Rate Limiting**
```java
// Limit total vendor API calls across all workers
RateLimiter vendorApiLimiter = RateLimiter.create(50.0); // 50 req/sec

public void syncPayment(UUID attemptId) {
    if (!vendorApiLimiter.tryAcquire(1, TimeUnit.SECONDS)) {
        log.warn("Rate limit exceeded, requeueing {}", attemptId);
        scheduleRetry(attemptId, Duration.ofSeconds(30));
        return;
    }
    // Proceed with sync
}
```

---

### 8. Thread Pool Configuration Missing

**Problem**: Spring `@Async` uses inadequate defaults for production workloads.

**Default Configuration Issues**:
```java
// Spring Boot @Async defaults:
Core pool size: 8
Max pool size: Integer.MAX_VALUE
Queue capacity: Integer.MAX_VALUE
Keep-alive: 60s
```

**Failure Scenario**:
```
1000 pending payments trigger async workers
→ Queue grows unbounded
→ OutOfMemoryError or application freeze
→ All requests time out
```

**Required Configuration**:
```java
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {
    
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // Core threads always alive
        executor.setCorePoolSize(10);
        
        // Max threads under load
        executor.setMaxPoolSize(50);
        
        // Bounded queue prevents memory issues
        executor.setQueueCapacity(100);
        
        // Reject policy when queue full
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        // Observability
        executor.setThreadNamePrefix("payment-sync-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        
        executor.initialize();
        return executor;
    }
    
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) -> {
            log.error("Async exception in {}: {}", method.getName(), throwable.getMessage(), throwable);
            // Send alert to monitoring system
        };
    }
}
```

**Rejected Execution Policies**:
- `AbortPolicy` (default): Throws exception - can cause cascading failures
- `CallerRunsPolicy`: Caller thread executes - provides backpressure
- `DiscardPolicy`: Silently drops task - use with caution
- **Recommended**: `CallerRunsPolicy` with alerting on rejections

---

### 9. No Error Handling or Observability

**Problem**: Missing critical operational capabilities.

**Gaps**:
- What happens if vendor API returns 500?
- What if network timeout occurs?
- How do you detect stuck locks?
- How do you measure sync success rate?
- How do you alert on vendor API degradation?

**Required Observability**:

**A. Metrics**
```java
@Timed(value = "payment.sync.duration", percentiles = {0.5, 0.95, 0.99})
@Counted(value = "payment.sync.attempts")
public void syncPayment(UUID paymentId) {
    Timer.Sample sample = Timer.start(meterRegistry);
    
    try {
        // Sync logic
        meterRegistry.counter("payment.sync.success").increment();
        
    } catch (VendorApiException e) {
        meterRegistry.counter("payment.sync.failure", 
            "error_type", e.getErrorType()).increment();
        
        if (e.isRetryable()) {
            scheduleRetry(paymentId);
        } else {
            alertService.sendAlert("Non-retryable vendor error", e);
        }
        
    } finally {
        sample.stop(Timer.builder("payment.sync.duration")
            .tag("payment_id", paymentId.toString())
            .register(meterRegistry));
        releaseLock(paymentId);
    }
}
```

**B. Structured Logging**
```java
@Slf4j
public class PaymentSyncWorker {
    
    public void syncPayment(UUID attemptId) {
        MDC.put("payment_attempt_id", attemptId.toString());
        MDC.put("worker_id", workerId);
        
        log.info("Starting payment sync");
        
        try {
            // Sync logic
            log.info("Payment sync completed successfully", 
                kv("vendor_status", vendorStatus),
                kv("sync_duration_ms", duration));
                
        } catch (Exception e) {
            log.error("Payment sync failed", e,
                kv("retry_attempt", retryCount),
                kv("vendor_error_code", e.getVendorErrorCode()));
        } finally {
            MDC.clear();
        }
    }
}
```

**C. Health Checks**
```java
@Component
public class PaymentSyncHealthIndicator implements HealthIndicator {
    
    @Override
    public Health health() {
        long stuckLocks = lockRepository.countLocksOlderThan(
            now().minus(10, MINUTES)
        );
        
        if (stuckLocks > 10) {
            return Health.down()
                .withDetail("stuck_locks", stuckLocks)
                .build();
        }
        
        // Check vendor API health
        if (!vendorClient.healthCheck()) {
            return Health.down()
                .withDetail("vendor_api", "unreachable")
                .build();
        }
        
        return Health.up().build();
    }
}
```

---

## Recommended Alternative Architectures

### Option A: Redis-Based Distributed Lock + Scheduled Sync

**Architecture**:

```
┌─────────┐
│ Client  │
└────┬────┘
     │ GET /orders/{id}
     ▼
┌─────────────────┐
│ Order API       │
│ (Read Replica)  │
└────┬────────────┘
     │
     ├─1. Check Redis cache (fresh?)
     ├─2. If miss: read DB replica
     ├─3. If non-terminal: publish to Redis Pub/Sub
     └─4. Return immediately
     
           │
           ▼
     ┌──────────────┐
     │ Redis Pub/Sub│
     └──────┬───────┘
            │
            ▼
     ┌─────────────────────┐
     │ Sync Worker Service │
     │ (Separate Pods)     │
     └──────┬──────────────┘
            │
            ├─1. Acquire Redis lock (SETNX)
            ├─2. Fetch vendor API
            ├─3. Update master DB
            ├─4. Cache result in Redis
            └─5. Release lock
            
     ┌─────────────────────┐
     │ Scheduled Job       │
     │ (Every 10 minutes)  │
     └──────┬──────────────┘
            │
            └─Find non-terminal payments not synced in 10min
              → Publish to Redis Pub/Sub
```

**Implementation**:

```java
// GET Endpoint
@GetMapping("/orders/{id}")
public OrderResponse getOrder(@PathVariable UUID id, 
                              @RequestParam boolean includePayments) {
    Order order = orderService.findById(id);
    
    if (includePayments && order.hasNonTerminalPayments()) {
        // Try cache first
        Optional<PaymentStatus> cached = cacheService.getPaymentStatus(order.getPaymentId());
        
        if (cached.isPresent() && cached.get().isFresh()) {
            order.setPaymentStatus(cached.get());
        } else {
            // Read from replica
            PaymentAttempt attempt = paymentRepo.findById(order.getPaymentId());
            
            // Trigger async sync if stale
            if (attempt.needsSync()) {
                syncEventPublisher.publish(new PaymentSyncRequested(attempt.getId()));
            }
            
            order.setPaymentStatus(attempt.getStatus());
        }
    }
    
    return orderMapper.toResponse(order);
}

// Sync Worker
@Service
public class PaymentSyncWorker {
    
    @RedisListener(topics = "payment-sync-requests")
    public void handleSyncRequest(PaymentSyncRequested event) {
        UUID attemptId = event.getPaymentAttemptId();
        String lockKey = "sync:payment:" + attemptId;
        
        // Try distributed lock
        boolean acquired = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, workerId, Duration.ofMinutes(5));
        
        if (!acquired) {
            log.debug("Lock already held for {}, skipping", attemptId);
            return;
        }
        
        try {
            PaymentAttempt attempt = paymentRepo.findById(attemptId);
            
            // Check if sync still needed (could have been updated by webhook)
            if (!attempt.needsSync()) {
                return;
            }
            
            // Fetch from vendor
            VendorStatusResponse vendorStatus = vendorClient.getStatus(attempt.getVendorRef());
            
            // Update with optimistic lock
            int updated = paymentRepo.updateWithVersion(
                attemptId,
                vendorStatus.getStatus(),
                attempt.getVersion(),
                now()
            );
            
            if (updated == 0) {
                log.warn("Payment {} updated concurrently (webhook?), skipping", attemptId);
                return;
            }
            
            // Update cache
            cacheService.setPaymentStatus(attemptId, vendorStatus.getStatus(), Duration.ofMinutes(2));
            
        } finally {
            redisTemplate.delete(lockKey);
        }
    }
}

// Scheduled fallback
@Scheduled(fixedDelay = 600000) // 10 minutes
public void syncStalePendingPayments() {
    List<PaymentAttempt> stale = paymentRepo
        .findByStatusInAndLastSyncedAtBefore(
            List.of(PENDING, PROCESSING),
            now().minus(10, MINUTES)
        );
    
    stale.forEach(attempt -> 
        syncEventPublisher.publish(new PaymentSyncRequested(attempt.getId()))
    );
}
```

**Benefits**:
- ✅ No database writes during GET request processing
- ✅ True CQRS with read replicas
- ✅ Distributed locking across multiple pods/containers
- ✅ Natural deduplication via Redis
- ✅ Can scale sync workers independently
- ✅ Fast cache-first reads

**Drawbacks**:
- ❌ Introduces Redis dependency
- ❌ More complex deployment topology
- ❌ Pub/Sub requires message ordering guarantees

---

### Option B: Outbox Pattern with Debounced Queue

**Architecture**:

```
┌─────────┐
│ Client  │
└────┬────┘
     │ GET /orders/{id}
     ▼
┌─────────────────────────────┐
│ Order API                   │
│                             │
│ 1. Read from DB             │
│ 2. Write to outbox (TX)     │
│ 3. Return immediately       │
└─────────────┬───────────────┘
              │
              ▼
        ┌────────────────┐
        │ Outbox Table   │
        │ payment_id (PK)│
        │ requested_at   │
        └────────┬───────┘
                 │
                 ▼ Polls every 5s
        ┌─────────────────────┐
        │ Outbox Processor    │
        │ (Background Service)│
        └──────┬──────────────┘
               │
               ├─1. SELECT with FOR UPDATE SKIP LOCKED
               ├─2. Group by payment_id (dedupe)
               ├─3. Process batch with rate limiting
               ├─4. Update master DB
               └─5. DELETE from outbox
```

**Implementation**:

```java
// GET Endpoint
@GetMapping("/orders/{id}")
@Transactional
public OrderResponse getOrder(@PathVariable UUID id) {
    Order order = orderService.findById(id);
    
    if (order.hasNonTerminalPayments()) {
        // Write to outbox in same transaction
        PaymentAttempt attempt = order.getPaymentAttempts().get(0);
        
        if (attempt.needsSync()) {
            syncOutboxRepo.upsert(new PaymentSyncOutbox(
                attempt.getId(),
                now()
            ));
        }
    }
    
    return orderMapper.toResponse(order);
}

// Outbox table
@Entity
@Table(name = "payment_sync_outbox")
public class PaymentSyncOutbox {
    @Id
    private UUID paymentAttemptId;
    
    private LocalDateTime requestedAt;
    private LocalDateTime lockedAt;
    private String lockedBy;
}

// Repository with upsert
@Repository
public interface SyncOutboxRepository extends JpaRepository<PaymentSyncOutbox, UUID> {
    
    @Modifying
    @Query("""
        INSERT INTO payment_sync_outbox (payment_attempt_id, requested_at)
        VALUES (:#{#outbox.paymentAttemptId}, :#{#outbox.requestedAt})
        ON CONFLICT (payment_attempt_id) 
        DO UPDATE SET requested_at = GREATEST(payment_sync_outbox.requested_at, EXCLUDED.requested_at)
        """)
    void upsert(@Param("outbox") PaymentSyncOutbox outbox);
    
    @Query(value = """
        SELECT * FROM payment_sync_outbox
        WHERE locked_at IS NULL OR locked_at < NOW() - INTERVAL '5 minutes'
        ORDER BY requested_at
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<PaymentSyncOutbox> findNextBatch(@Param("batchSize") int batchSize);
}

// Outbox Processor
@Service
public class PaymentSyncOutboxProcessor {
    
    private final RateLimiter vendorApiRateLimiter = RateLimiter.create(50.0); // 50 req/sec
    
    @Scheduled(fixedDelay = 5000) // Every 5 seconds
    @Transactional
    public void processBatch() {
        List<PaymentSyncOutbox> batch = syncOutboxRepo.findNextBatch(20);
        
        if (batch.isEmpty()) {
            return;
        }
        
        // Mark as locked
        batch.forEach(outbox -> {
            outbox.setLockedAt(now());
            outbox.setLockedBy(hostName);
        });
        syncOutboxRepo.saveAll(batch);
        
        // Process each (deduplicated by PK)
        batch.forEach(outbox -> {
            try {
                // Rate limiting
                vendorApiRateLimiter.acquire();
                
                // Sync payment
                syncService.syncPayment(outbox.getPaymentAttemptId());
                
                // Remove from outbox
                syncOutboxRepo.delete(outbox);
                
            } catch (Exception e) {
                log.error("Failed to sync {}", outbox.getPaymentAttemptId(), e);
                
                // Reset lock on failure (will retry next batch)
                outbox.setLockedAt(null);
                outbox.setLockedBy(null);
                syncOutboxRepo.save(outbox);
            }
        });
    }
}

// Scheduled sync for long-running payments
@Scheduled(fixedDelay = 1800000) // Every 30 minutes
@Transactional
public void syncStalePendingPayments() {
    List<PaymentAttempt> stale = paymentRepo.findNonTerminalOlderThan(
        now().minus(30, MINUTES)
    );
    
    List<PaymentSyncOutbox> outboxEntries = stale.stream()
        .map(attempt -> new PaymentSyncOutbox(attempt.getId(), now()))
        .toList();
    
    outboxEntries.forEach(syncOutboxRepo::upsert);
}
```

**Benefits**:
- ✅ Transactional outbox ensures no lost events (atomicity with business TX)
- ✅ Natural batching and deduplication (PK constraint)
- ✅ `FOR UPDATE SKIP LOCKED` prevents contention between processors
- ✅ Can evolve to Kafka/RabbitMQ CDC later (Debezium)
- ✅ Separates read scaling from sync scaling
- ✅ No external dependencies (pure PostgreSQL)

**Drawbacks**:
- ❌ Slightly higher write load on master DB (outbox inserts)
- ❌ Requires separate outbox processor deployment
- ❌ 5-second polling delay (tune based on requirements)

---

### Option C: Event Sourcing with Conflict Resolution

**Architecture**:

```
┌─────────┐
│ Client  │
└────┬────┘
     │ GET /orders/{id}
     ▼
┌──────────────────────────┐
│ Order API                │
│ 1. Read materialized view│
│ 2. Append PollRequested  │
│    event (async)         │
│ 3. Return immediately    │
└──────────┬───────────────┘
           │
           ▼
     ┌────────────────────┐
     │ Event Store        │
     │ payment_status_    │
     │ events (append-only│
     └──────┬─────────────┘
            │
            ├─ Webhook → WebhookReceived event
            ├─ Worker  → PolledStatus event
            └─ User    → UserCancelled event
            
            │
            ▼
     ┌──────────────────────┐
     │ Event Processor      │
     │ (Ordered by sequence)│
     └──────┬───────────────┘
            │
            └─ Apply conflict resolution
               → Update materialized view
```

**Schema**:

```sql
-- Event Store (source of truth)
CREATE TABLE payment_status_events (
    event_id UUID PRIMARY KEY,
    payment_attempt_id UUID NOT NULL,
    sequence_number BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    status VARCHAR(20),
    source VARCHAR(20) NOT NULL, -- WEBHOOK, POLL, USER
    vendor_ref VARCHAR(100),
    timestamp TIMESTAMPTZ NOT NULL,
    metadata JSONB,
    
    UNIQUE (payment_attempt_id, sequence_number)
);

-- Materialized View (query model)
CREATE TABLE payment_attempts (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    last_event_sequence BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
```

**Implementation**:

```java
// GET Endpoint
@GetMapping("/orders/{id}")
public OrderResponse getOrder(@PathVariable UUID id) {
    Order order = orderService.findById(id); // Reads materialized view
    
    if (order.hasNonTerminalPayments()) {
        PaymentAttempt attempt = order.getPaymentAttempts().get(0);
        
        // Append event asynchronously (non-blocking)
        eventPublisher.publish(new PollRequestedEvent(
            attempt.getId(),
            now()
        ));
    }
    
    return orderMapper.toResponse(order);
}

// Event Processor
@Service
public class PaymentEventProcessor {
    
    @TransactionalEventListener
    @Async
    public void handlePollRequested(PollRequestedEvent event) {
        UUID attemptId = event.getPaymentAttemptId();
        
        // Append to event store
        PaymentStatusEvent pollEvent = eventStore.append(
            PaymentStatusEvent.builder()
                .paymentAttemptId(attemptId)
                .eventType(EventType.POLL_REQUESTED)
                .source(EventSource.POLL)
                .timestamp(now())
                .build()
        );
        
        // Trigger async worker
        asyncExecutor.execute(() -> {
            try {
                // Fetch from vendor
                VendorStatusResponse response = vendorClient.getStatus(attemptId);
                
                // Append result event
                eventStore.append(
                    PaymentStatusEvent.builder()
                        .paymentAttemptId(attemptId)
                        .eventType(EventType.STATUS_POLLED)
                        .source(EventSource.POLL)
                        .status(response.getStatus())
                        .timestamp(now())
                        .metadata(response.getRawData())
                        .build()
                );
                
                // Projection will update materialized view
                
            } catch (Exception e) {
                eventStore.append(
                    PaymentStatusEvent.builder()
                        .paymentAttemptId(attemptId)
                        .eventType(EventType.POLL_FAILED)
                        .source(EventSource.POLL)
                        .timestamp(now())
                        .metadata(Map.of("error", e.getMessage()))
                        .build()
                );
            }
        });
    }
    
    @TransactionalEventListener
    public void handleWebhook(WebhookReceivedEvent event) {
        eventStore.append(
            PaymentStatusEvent.builder()
                .paymentAttemptId(event.getPaymentAttemptId())
                .eventType(EventType.WEBHOOK_RECEIVED)
                .source(EventSource.WEBHOOK)
                .status(event.getStatus())
                .vendorRef(event.getVendorRef())
                .timestamp(event.getReceivedAt())
                .build()
        );
    }
}

// Projection (updates materialized view)
@Service
public class PaymentAttemptProjection {
    
    @TransactionalEventListener
    public void project(PaymentStatusEvent event) {
        // Conflict resolution: webhook wins over poll if within 1 minute
        List<PaymentStatusEvent> recentEvents = eventStore
            .findByPaymentAttemptIdAndTimestampAfter(
                event.getPaymentAttemptId(),
                event.getTimestamp().minus(1, MINUTES)
            );
        
        PaymentStatusEvent winningEvent = conflictResolver.resolve(recentEvents);
        
        // Update materialized view
        paymentRepo.updateStatus(
            event.getPaymentAttemptId(),
            winningEvent.getStatus(),
            winningEvent.getSequenceNumber()
        );
    }
}

// Conflict Resolver
@Component
public class PaymentEventConflictResolver {
    
    public PaymentStatusEvent resolve(List<PaymentStatusEvent> events) {
        if (events.size() == 1) {
            return events.get(0);
        }
        
        // Priority: WEBHOOK > USER > POLL
        return events.stream()
            .sorted(Comparator
                .comparing(this::getSourcePriority)
                .thenComparing(PaymentStatusEvent::getTimestamp))
            .findFirst()
            .orElseThrow();
    }
    
    private int getSourcePriority(PaymentStatusEvent event) {
        return switch (event.getSource()) {
            case WEBHOOK -> 1;
            case USER -> 2;
            case POLL -> 3;
        };
    }
}
```

**Benefits**:
- ✅ Full audit trail of all status changes
- ✅ Deterministic conflict resolution (replaying events gives same result)
- ✅ Can debug issues by replaying event stream
- ✅ Guaranteed eventual consistency
- ✅ Can rebuild materialized view from events if corrupted

**Drawbacks**:
- ❌ Most complex implementation
- ❌ Requires careful event versioning strategy
- ❌ Event store can grow large (need archival strategy)
- ❌ Learning curve for team unfamiliar with event sourcing

---

## Recommended Implementation: Hybrid Approach for MVP

Based on risk-reward analysis, I recommend a **pragmatic hybrid** that:
- Minimizes infrastructure changes (no Redis/Kafka required)
- Addresses all critical issues identified
- Provides clear migration path to more sophisticated solutions

### Phase 1: Core Implementation

**1. Database Schema Changes**

```sql
-- Add sync tracking to payment_attempts
ALTER TABLE payment_attempts 
ADD COLUMN last_synced_at TIMESTAMPTZ,
ADD COLUMN sync_version INT DEFAULT 0 NOT NULL,
ADD COLUMN updated_by VARCHAR(20); -- 'WEBHOOK' or 'POLL' for audit trail

-- Index for scheduled sync query
CREATE INDEX idx_payment_attempts_pending_sync 
ON payment_attempts(status, last_synced_at) 
WHERE status IN ('PENDING', 'PROCESSING');

-- Lock table (optional - can use Redis later)
CREATE TABLE payment_sync_locks (
    payment_attempt_id UUID PRIMARY KEY,
    locked_at TIMESTAMPTZ NOT NULL,
    locked_by VARCHAR(100) NOT NULL,
    heartbeat_at TIMESTAMPTZ NOT NULL
);

-- Index for stuck lock detection
CREATE INDEX idx_sync_locks_heartbeat 
ON payment_sync_locks(heartbeat_at);
```

**2. Domain Model Updates**

```java
@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt {
    @Id
    private UUID id;
    
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;
    
    private LocalDateTime lastSyncedAt;
    
    @Version
    private Integer syncVersion; // Optimistic locking
    
    private String updatedBy; // "WEBHOOK" or "POLL" for audit trail
    
    // Business logic
    public boolean isNonTerminal() {
        return status == PENDING || status == PROCESSING;
    }
    
    public boolean isTerminal() {
        return status == SUCCESS || status == FAILED;
    }
    
    public boolean needsSync() {
        if (!isNonTerminal()) {
            return false;
        }
        
        if (lastSyncedAt == null) {
            return true;
        }
        
        // Don't sync if synced within last 2 minutes
        return lastSyncedAt.isBefore(LocalDateTime.now().minusMinutes(2));
    }
    
    /**
     * Validates if a state transition is allowed.
     * Terminal states (SUCCESS, FAILED) cannot transition to other states.
     */
    public boolean canTransitionTo(PaymentStatus newStatus) {
        // Terminal states are immutable
        if (this.status.isTerminal()) {
            // Only allow idempotent updates (same status)
            return this.status == newStatus;
        }
        
        // Non-terminal states can transition to any state
        return true;
    }
}

@Getter
@AllArgsConstructor
public enum PaymentStatus {
    PENDING(false),
    PROCESSING(false),
    SUCCESS(true),
    FAILED(true);
    
    private final boolean terminal;
}
```

**3. GET Endpoint with Smart Triggering**

```java
@RestController
@RequiredArgsConstructor
public class OrderController {
    
    private final OrderService orderService;
    private final PaymentSyncOrchestrator syncOrchestrator;
    
    @GetMapping("/orders/{id}")
    public ResponseEntity<OrderResponse> getOrder(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean includePayments) {
        
        Order order = orderService.findById(id);
        
        if (includePayments) {
            List<PaymentAttempt> attempts = order.getPaymentAttempts();
            
            // Trigger sync for non-terminal payments (fire-and-forget)
            attempts.stream()
                .filter(PaymentAttempt::needsSync)
                .forEach(attempt -> {
                    try {
                        syncOrchestrator.triggerSyncIfNeeded(attempt.getId());
                    } catch (Exception e) {
                        // Don't fail the GET request if sync trigger fails
                        log.warn("Failed to trigger sync for {}", attempt.getId(), e);
                    }
                });
        }
        
        return ResponseEntity.ok(orderMapper.toResponse(order));
    }
}
```

**4. Sync Orchestrator with Pre-Flight Lock Check**

```java
@Service
@RequiredArgsConstructor
public class PaymentSyncOrchestrator {
    
    private final PaymentSyncLockRepository lockRepo;
    private final PaymentSyncWorker syncWorker;
    private final String hostName = InetAddress.getLocalHost().getHostName();
    
    /**
     * Checks if sync is already in progress before dispatching worker.
     * Prevents thundering herd by avoiding unnecessary async dispatch.
     */
    public void triggerSyncIfNeeded(UUID attemptId) {
        // Pre-flight check: is lock already held?
        Optional<PaymentSyncLock> existingLock = lockRepo.findById(attemptId);
        
        if (existingLock.isPresent() && !existingLock.get().isExpired()) {
            log.debug("Sync already in progress for {}, skipping", attemptId);
            return;
        }
        
        // Dispatch async worker
        syncWorker.syncPaymentAsync(attemptId);
    }
}
```

**5. Async Worker with Optimistic Locking**

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentSyncWorker {
    
    private final PaymentAttemptRepository paymentRepo;
    private final PaymentSyncLockRepository lockRepo;
    private final PaymentGatewayClient gatewayClient;
    private final MeterRegistry meterRegistry;
    private final String workerId = InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID();
    
    @Async("paymentSyncExecutor")
    @Transactional
    public void syncPaymentAsync(UUID attemptId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        MDC.put("payment_attempt_id", attemptId.toString());
        MDC.put("worker_id", workerId);
        
        try {
            log.info("Starting payment sync");
            
            // Step 1: Acquire lock
            boolean lockAcquired = tryAcquireLock(attemptId);
            if (!lockAcquired) {
                log.debug("Lock already held, skipping");
                meterRegistry.counter("payment.sync.skipped", "reason", "lock_held").increment();
                return;
            }
            
            try {
                // Step 2: Load current payment state
                PaymentAttempt attempt = paymentRepo.findById(attemptId)
                    .orElseThrow(() -> new PaymentNotFoundException(attemptId));
                
                // Step 3: Check if sync still needed (could have been updated)
                if (!attempt.needsSync()) {
                    log.debug("Payment no longer needs sync");
                    meterRegistry.counter("payment.sync.skipped", "reason", "already_synced").increment();
                    return;
                }
                
                // Step 4: Fetch from vendor API
                log.debug("Fetching status from vendor");
                VendorStatusResponse vendorStatus = gatewayClient.getPaymentStatus(
                    attempt.getVendorReference()
                );
                PaymentStatus newStatus = vendorStatus.getStatus();
                
                // Step 5: Pre-flight check for state transition validity (optional, for better logging)
                if (!attempt.canTransitionTo(newStatus)) {
                    log.info("Skipping invalid state transition: {} → {} (terminal state reached)",
                        attempt.getStatus(), newStatus);
                    meterRegistry.counter("payment.sync.skipped", 
                        "reason", "terminal_state").increment();
                    return;
                }
                
                // Step 6: Update with optimistic locking + state validation
                int updatedRows = paymentRepo.updateStatusWithValidation(
                    attemptId,
                    newStatus,
                    attempt.getSyncVersion(),
                    LocalDateTime.now(),
                    "POLL"
                );
                
                if (updatedRows == 0) {
                    // Either version conflict OR invalid state transition
                    // Reload to determine which
                    PaymentAttempt current = paymentRepo.findById(attemptId).orElseThrow();
                    
                    if (!current.getSyncVersion().equals(attempt.getSyncVersion())) {
                        log.info("Payment {} updated concurrently (version {} → {}), likely webhook",
                            attemptId, attempt.getSyncVersion(), current.getSyncVersion());
                        meterRegistry.counter("payment.sync.conflict", 
                            "reason", "version").increment();
                    } else {
                        log.info("Invalid state transition prevented: {} → {} (terminal state)",
                            current.getStatus(), newStatus);
                        meterRegistry.counter("payment.sync.conflict", 
                            "reason", "invalid_transition").increment();
                    }
                    return;
                }
                
                log.info("Payment sync completed successfully",
                    kv("old_status", attempt.getStatus()),
                    kv("new_status", newStatus));
                
                meterRegistry.counter("payment.sync.success").increment();
                
            } finally {
                // Step 6: Always release lock
                releaseLock(attemptId);
            }
            
        } catch (VendorApiException e) {
            log.error("Vendor API error during sync", e,
                kv("vendor_error_code", e.getErrorCode()),
                kv("is_retryable", e.isRetryable()));
            
            meterRegistry.counter("payment.sync.failure",
                "error_type", "vendor_api",
                "error_code", e.getErrorCode()).increment();
            
            // Could implement retry logic here
            
        } catch (Exception e) {
            log.error("Unexpected error during sync", e);
            meterRegistry.counter("payment.sync.failure",
                "error_type", "unexpected").increment();
            
        } finally {
            sample.stop(Timer.builder("payment.sync.duration")
                .tag("payment_attempt_id", attemptId.toString())
                .register(meterRegistry));
            MDC.clear();
        }
    }
    
    private boolean tryAcquireLock(UUID attemptId) {
        try {
            PaymentSyncLock lock = new PaymentSyncLock(
                attemptId,
                LocalDateTime.now(),
                workerId,
                LocalDateTime.now()
            );
            lockRepo.save(lock);
            return true;
            
        } catch (DataIntegrityViolationException e) {
            // Lock already exists
            return false;
        }
    }
    
    private void releaseLock(UUID attemptId) {
        try {
            lockRepo.deleteById(attemptId);
        } catch (Exception e) {
            log.error("Failed to release lock", e);
        }
    }
}
```

**6. Repository with Optimistic Locking + State Validation**

```java
@Repository
public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {
    
    /**
     * Updates payment status with BOTH optimistic locking AND state transition validation.
     * Prevents:
     * 1. Lost updates (version check)
     * 2. Invalid state transitions (terminal state protection)
     * 
     * Returns number of rows updated (0 if version conflict OR invalid transition).
     */
    @Modifying
    @Query("""
        UPDATE PaymentAttempt pa
        SET pa.status = :status,
            pa.syncVersion = pa.syncVersion + 1,
            pa.lastSyncedAt = :syncedAt,
            pa.updatedAt = :syncedAt,
            pa.updatedBy = :updatedBy
        WHERE pa.id = :id
          AND pa.syncVersion = :expectedVersion
          AND (
            -- Allow if current state is non-terminal (can transition)
            pa.status IN ('PENDING', 'PROCESSING')
            OR
            -- Allow idempotent updates (same status, e.g., SUCCESS → SUCCESS)
            pa.status = :status
          )
        """)
    int updateStatusWithValidation(
        @Param("id") UUID id,
        @Param("status") PaymentStatus status,
        @Param("expectedVersion") Integer expectedVersion,
        @Param("syncedAt") LocalDateTime syncedAt,
        @Param("updatedBy") String updatedBy
    );
    
    @Query("""
        SELECT pa FROM PaymentAttempt pa
        WHERE pa.status IN ('PENDING', 'PROCESSING')
          AND (pa.lastSyncedAt IS NULL 
               OR pa.lastSyncedAt < :threshold)
        ORDER BY pa.createdAt
        """)
    List<PaymentAttempt> findStaleNonTerminalPayments(
        @Param("threshold") LocalDateTime threshold
    );
}
```

**7. Scheduled Fallback Sync**

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduledPaymentSyncJob {
    
    private final PaymentAttemptRepository paymentRepo;
    private final PaymentSyncOrchestrator syncOrchestrator;
    
    /**
     * Syncs payments that are still pending after 10 minutes.
     * Prevents zombie payments when no GET requests happen.
     */
    @Scheduled(fixedDelay = 600000, initialDelay = 60000) // Every 10 min
    public void syncStalePendingPayments() {
        log.info("Starting scheduled sync of stale pending payments");
        
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);
        List<PaymentAttempt> stalePayments = paymentRepo.findStaleNonTerminalPayments(threshold);
        
        log.info("Found {} stale pending payments", stalePayments.size());
        
        stalePayments.forEach(attempt -> {
            try {
                syncOrchestrator.triggerSyncIfNeeded(attempt.getId());
            } catch (Exception e) {
                log.error("Failed to trigger sync for {}", attempt.getId(), e);
            }
        });
        
        log.info("Scheduled sync completed");
    }
    
    /**
     * Cleans up stuck locks (where worker died without releasing).
     */
    @Scheduled(fixedDelay = 300000) // Every 5 min
    public void cleanupStuckLocks() {
        LocalDateTime expiredThreshold = LocalDateTime.now().minusMinutes(10);
        
        int deleted = lockRepo.deleteByHeartbeatAtBefore(expiredThreshold);
        
        if (deleted > 0) {
            log.warn("Cleaned up {} stuck locks", deleted);
            meterRegistry.counter("payment.sync.locks.cleaned", "reason", "expired")
                .increment(deleted);
        }
    }
}
```

**8. Thread Pool Configuration**

```java
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {
    
    @Bean(name = "paymentSyncExecutor")
    public Executor paymentSyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // Core threads always alive
        executor.setCorePoolSize(10);
        
        // Max threads under load
        executor.setMaxPoolSize(50);
        
        // Bounded queue prevents memory issues
        executor.setQueueCapacity(100);
        
        // When queue full, caller thread executes (backpressure)
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        // Observability
        executor.setThreadNamePrefix("payment-sync-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        
        executor.initialize();
        return executor;
    }
    
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) -> {
            log.error("Uncaught async exception in {}: {}", 
                method.getName(), throwable.getMessage(), throwable);
            
            // Send alert to monitoring system
            alertService.sendAlert(
                "Async Payment Sync Exception",
                throwable,
                Map.of("method", method.getName())
            );
        };
    }
}
```

---

### Phase 2: Enhancements

Once the core implementation is stable, add these enhancements:

**1. Vendor API Circuit Breaker**

```java
@Service
public class PaymentGatewayClient {
    
    @CircuitBreaker(name = "paymentVendor", fallbackMethod = "getStatusFallback")
    @RateLimiter(name = "paymentVendor")
    @Retry(name = "paymentVendor", fallbackMethod = "getStatusFallback")
    @Timed("vendor.api.status.check")
    public VendorStatusResponse getPaymentStatus(String vendorRef) {
        return vendorApi.checkStatus(vendorRef);
    }
    
    private VendorStatusResponse getStatusFallback(String vendorRef, Exception e) {
        log.warn("Vendor API unavailable, using fallback for {}", vendorRef);
        
        // Return last known status or placeholder
        return VendorStatusResponse.unavailable();
    }
}

// application.yml
resilience4j:
  circuitbreaker:
    instances:
      paymentVendor:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 3
  
  ratelimiter:
    instances:
      paymentVendor:
        limitForPeriod: 50
        limitRefreshPeriod: 1s
        timeoutDuration: 100ms
  
  retry:
    instances:
      paymentVendor:
        maxAttempts: 3
        waitDuration: 500ms
        exponentialBackoffMultiplier: 2
```

**2. Exponential Backoff for Long-Running Payments**

```java
@Component
public class ExponentialBackoffSyncScheduler {
    
    /**
     * Poll frequently early, then slow down.
     * 0-10 min: every 2 min
     * 10-60 min: every 10 min
     * 1-24 hr: every 30 min
     * >24 hr: stop (alert for manual review)
     */
    public Duration calculateNextSyncDelay(PaymentAttempt attempt) {
        Duration age = Duration.between(attempt.getCreatedAt(), LocalDateTime.now());
        
        if (age.toMinutes() < 10) {
            return Duration.ofMinutes(2);
        } else if (age.toMinutes() < 60) {
            return Duration.ofMinutes(10);
        } else if (age.toHours() < 24) {
            return Duration.ofMinutes(30);
        } else {
            // Alert and stop polling
            alertService.sendAlert("Payment stuck for >24 hours", attempt);
            return Duration.ofDays(999); // Effectively stop
        }
    }
    
    @Scheduled(fixedDelay = 120000) // Every 2 min
    public void syncRecentPendingPayments() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(2);
        List<PaymentAttempt> recent = paymentRepo.findNonTerminalCreatedAfter(
            LocalDateTime.now().minusMinutes(10)
        );
        
        recent.stream()
            .filter(attempt -> attempt.getLastSyncedAt() == null ||
                             attempt.getLastSyncedAt().isBefore(threshold))
            .forEach(attempt -> syncOrchestrator.triggerSyncIfNeeded(attempt.getId()));
    }
}
```

**3. Caching Layer (Optional)**

```java
@Service
@RequiredArgsConstructor
public class CachedPaymentStatusService {
    
    private final RedisTemplate<String, PaymentStatus> redisTemplate;
    private final PaymentAttemptRepository paymentRepo;
    
    public Optional<PaymentStatus> getCachedStatus(UUID attemptId) {
        String key = "payment:status:" + attemptId;
        PaymentStatus cached = redisTemplate.opsForValue().get(key);
        return Optional.ofNullable(cached);
    }
    
    public void cacheStatus(UUID attemptId, PaymentStatus status, Duration ttl) {
        String key = "payment:status:" + attemptId;
        redisTemplate.opsForValue().set(key, status, ttl);
    }
    
    public void invalidateCache(UUID attemptId) {
        String key = "payment:status:" + attemptId;
        redisTemplate.delete(key);
    }
}

// In GET endpoint
Optional<PaymentStatus> cached = cacheService.getCachedStatus(attemptId);
if (cached.isPresent()) {
    return cached.get();
}

// In sync worker (after successful update)
cacheService.cacheStatus(attemptId, newStatus, Duration.ofMinutes(2));

// In webhook handler (after update)
cacheService.invalidateCache(attemptId);
```

---

## Summary of Protections

| Issue | Mitigation |
|-------|-----------|
| **Webhook vs Worker Race** | Optimistic locking + State machine validation (terminal states immutable) |
| **Thundering Herd** | Pre-flight lock check + rate limiting |
| **Lock Granularity** | Attempt-level locking with unique constraint |
| **One-Time Sync** | Scheduled fallback job every 10 min |
| **Stale Locks** | Heartbeat-based cleanup + TTL |
| **Read Replica Lag** | Accept eventual consistency or add cache |
| **Rate Limiting** | Last-synced-at check + circuit breaker |
| **Thread Pool** | Bounded queue + CallerRunsPolicy |
| **Error Handling** | Try-catch + metrics + alerting |
| **Vendor API Failures** | Circuit breaker + retry + fallback |
| **Invalid State Transitions** | DB-level validation prevents terminal state corruption |

---

## Migration Path

**Today (MVP)**:
- Hybrid approach with DB locks
- Optimistic locking + state machine validation for conflict resolution
- Terminal state immutability (SUCCESS/FAILED cannot be overwritten)
- Scheduled fallback sync

**Phase 2 (Scale)**:
- Add Redis for distributed locks
- Implement caching layer
- Move to read replicas

**Phase 3 (High Scale)**:
- Migrate to Outbox + CDC (Debezium)
- Separate sync service (microservice)
- Kafka for event streaming

---

## Conclusion

The original proposal (doc 007) has good intentions but critical gaps that would cause production issues:

- ❌ Race conditions between webhooks and workers
- ❌ Thundering herd not prevented
- ❌ No handling for long-running payments
- ❌ Insufficient lock cleanup
- ❌ Missing observability

The **Hybrid Approach** recommended here:

- ✅ Addresses all critical issues
- ✅ No new infrastructure required
- ✅ Can be implemented incrementally
- ✅ Clear migration path to more sophisticated patterns
- ✅ Production-ready with proper monitoring

**Recommended Action**: Implement the Hybrid Approach for MVP, then migrate to Option A (Redis) or Option B (Outbox) as traffic scales.
