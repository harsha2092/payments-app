# Plan: Order & Payment Async Status Sync

## 1. Context & Problem Statement
Currently, a call to `GET /orders/{id}?includePayments=true` retrieves the payment data natively from the database. However, this data may become stale if the user drops off and the third-party payment vendor hasn’t fired a webhook. While we want to provide the user with the most up-to-date order status, running an external HTTP call to a vendor API natively inside a Read (GET) API endpoint is an anti-pattern. It bloats response times, breaks CQRS if we want to point the GET API exclusively to database Read Replicas, and introduces points of failure.

Furthermore, we must prevent the **Thundering Herd** problem: if a user or system repeatedly polls the `GET` endpoint, we cannot trigger concurrent backend sync routines for the exact same order or payment. Only ONE background worker should execute a sync per order.

## 2. Proposed Architecture Overview

We propose an **Event-Driven Async Synchronization** model utilizing a purely pessimistic database lock or an atomic `ON CONFLICT` strategy on the Master Database to orchestrate concurrency, alongside Spring’s native `@Async` threading for the background execution.

### High-Level Flow
1. **The Read Request**: Client calls `GET /orders/{id}?includePayments=true`.
2. **Immediate Return**: The endpoint immediately reads the Order and Payments from the DB (ideally from a Read Replica in the future) and returns this to the client.
3. **Trigger Evaluation**: Before returning the response, the system evaluates the `PaymentAttempts`. If any attempt is in a non-terminal state (e.g., `PENDING`), it dispatches an internal event to trigger the worker.
4. **Concurrency Control (Deduplication)**: The triggered worker first attempts to atomically acquire a logical lock on the Master DB (via an explicit insert-lock row or an atomic update on `PaymentAttempt.isSyncing`). If it acquires the lock, it proceeds. If it fails, it gracefully exits.
5. **Background Sync**: The worker executes a standard status sync against the payment vendor and updates the database locally via the Master. Once complete, it releases the lock. Future `GET` requests will recognize the new terminal status directly from the DB.

---

## 3. Implementation Components

### A. Non-Terminal Status Detection
A helper method in `PaymentAttemptStatus` to evaluate terminal vs non-terminal states.
- Terminal: `SUCCESS`, `FAILED`
- Non-Terminal: `PENDING`, `PROCESSING`

### B. Concurrency Lock Table Strategy
We will create a lightweight coordination table to track synchronizations.
**Table: `payment_sync_locks`**
- `payment_id` (UUID - Primary Key)
- `locked_at` (LocalDateTime)

*Why a dedicated lock table?* It isolates locking actions from the high-read payment table, minimizing row updates and index fragmentation on `payment_attempts`. Alternatively, we can use a distributed lock cache (Redis) if introduced later.

### C. The Background Worker (`PaymentSyncWorker`)
A dedicated `@Service` component responsible for syncing the payment.
1. The `GET` endpoint fires an async call (e.g., `paymentSyncWorker.syncPaymentInBackground(paymentId)`).
2. The worker attempts an atomic `INSERT INTO payment_sync_locks (payment_id, locked_at) VALUES (...)`. If it raises a Unique Constraint error, the lock is already held. Thread terminates.
3. If successful, the worker queries the `PaymentGatewayClient`.
4. It maps the returned payload and executes the standard `updatePaymentStatus()` flow.
5. The worker ends its thread by deleting its row from `payment_sync_locks`.

---

## 4. Alternate Considerations
- **Stale Lock Cleanup**: If a container dies inside a sync, the lock becomes stuck. We must implement a lock TTL. Either the `INSERT` does `ON CONFLICT (payment_id) DO UPDATE SET locked_at = CURRENT_TIMESTAMP WHERE locked_at < NOW() - 5 MINUTES`, forcing an overwrite on dead locks.
- **Message Broker instead of `@Async`**: Utilizing an Outbox pattern with Kafka/RabbitMQ is more scalable, though overkill for local MVP. The locking DB table replicates the queuing concept efficiently without major infrastructure changes.
