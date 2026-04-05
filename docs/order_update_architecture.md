
# Order Update Architecture
## Technical Design Document (TDD)

Version: v1.0
Scope: Order update handling, concurrency, audit, and state lifecycle

---

# 1. Problem Statement

Once an order is created (`PENDING`), multiple sources can trigger updates:

- **Internal API**: Our own frontend/backend updates the order after user action (e.g. user selects a payment method, clicks pay).
- **Vendor Webhook**: The payment vendor (Stripe, Razorpay, etc.) sends asynchronous callbacks with payment results.
- **Admin/Ops**: Manual corrections or refund triggers.

These updates can arrive:

- Out of order
- Concurrently (webhook + API at the same millisecond)
- With duplicate delivery (vendors often retry webhooks)

We need a design that handles all of this cleanly without losing data.

---

# 2. Core Decision: Update-in-Place + Append Audit Log

## Strategy

**Hybrid approach**: Update the `orders` table in-place AND append every state change to a separate `order_events` audit log table.

### Why not append-only on orders?

- Append-only (event sourcing) means every read requires replaying or materializing the latest state.
- Adds complexity to every GET query (need to find latest row, aggregate fields).
- The `orders` table is our hot read path — it must return the current state in a single row lookup.

### Why not update-only without audit?

- We lose the history of how an order moved through states.
- Debugging payment issues becomes impossible ("when did status change to FAILED? what was the vendor response?").
- Compliance and dispute resolution require state change history.

### Final Model

```
orders table          → single row per order, always reflects CURRENT state (update-in-place)
order_events table    → append-only log of every state change (immutable audit trail)
```

---

# 3. Order State Lifecycle

## States

```
PENDING → PROCESSING → SUCCESS
                     → FAILED → PENDING (retry)
PENDING → EXPIRED
SUCCESS → REFUND_INITIATED → REFUNDED
```

## State Transition Matrix

| From               | To                 | Trigger                     |
|--------------------|--------------------|-----------------------------|
| PENDING            | PROCESSING         | Payment initiated with vendor |
| PROCESSING         | SUCCESS            | Vendor confirms payment      |
| PROCESSING         | FAILED             | Vendor reports failure       |
| FAILED             | PENDING            | User retries payment         |
| PENDING            | EXPIRED            | TTL exceeded (scheduled job) |
| SUCCESS            | REFUND_INITIATED   | Refund requested             |
| REFUND_INITIATED   | REFUNDED           | Vendor confirms refund       |

## Invalid Transitions (Rejected)

- `SUCCESS → PENDING` (cannot un-pay)
- `EXPIRED → PROCESSING` (must create new order)
- `REFUNDED → SUCCESS` (cannot un-refund)
- Any transition not in the matrix above

The application MUST enforce this matrix. Any update request that violates it is rejected with `409 Conflict`.

---

# 4. Database Schema Changes

## 4.1 Modified `orders` Table

Add a `version` column for optimistic locking and an `updated_at` timestamp:

```sql
ALTER TABLE orders ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT now();
```

Full schema after change:

```sql
CREATE TABLE orders (
    id UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT now(),

    user_id UUID NOT NULL,

    amount BIGINT NOT NULL,
    currency TEXT NOT NULL,

    payment_method TEXT NOT NULL,
    vendor TEXT,

    status TEXT NOT NULL,

    vendor_order_id TEXT,

    metadata JSONB,

    version BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY(id)
);
```

## 4.2 New `order_events` Table (Audit Log)

```sql
CREATE TABLE order_events (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL,

    previous_status TEXT NOT NULL,
    new_status TEXT NOT NULL,

    source TEXT NOT NULL,           -- 'API', 'WEBHOOK', 'SYSTEM', 'ADMIN'
    source_ip TEXT,

    vendor_payload JSONB,           -- raw vendor webhook body (stored as-is)
    event_metadata JSONB,           -- any additional context

    created_at TIMESTAMP NOT NULL DEFAULT now(),

    PRIMARY KEY(id)
);

CREATE INDEX idx_order_events_order_id ON order_events(order_id);
CREATE INDEX idx_order_events_created_at ON order_events(created_at);
```

Key properties:

- **Immutable**: Rows are only ever inserted, never updated or deleted.
- **Complete**: Every field change on the order is captured.
- **Traceable**: `source` tells us whether it was an API call, webhook, or system job.

---

# 5. Concurrency Control: Optimistic Locking

## Why Optimistic over Pessimistic?

- Payment updates are infrequent per order (typically 2-4 state changes in an order's lifetime).
- Pessimistic locks (`SELECT ... FOR UPDATE`) hold DB row locks, reducing throughput under concurrent load.
- Optimistic locking is lock-free on reads and only blocks on conflicting writes — perfect for our access pattern.

## How It Works

1. Read the order (includes `version` field).
2. Validate the state transition.
3. Update the order with `WHERE id = ? AND version = ?`.
4. If `affected_rows == 0` → someone else updated first → retry.

### JPA Implementation

```java
@Entity
@Table(name = "orders")
public class Order {

    @Version
    private Long version;

    // ... existing fields
}
```

Hibernate automatically:
- Includes `AND version = ?` in UPDATE queries.
- Throws `OptimisticLockException` if the version doesn't match.
- Increments `version` on every successful update.

## Retry Strategy on Conflict

When `OptimisticLockException` is caught:

1. Re-read the order from DB (get fresh state + version).
2. Re-validate the state transition against the NEW current state.
3. If transition is still valid → retry the update (max 3 retries).
4. If transition is NOW invalid (e.g. order already moved to SUCCESS) → return appropriate response (idempotent success or conflict).

```
attempt update
  ↓
OptimisticLockException?
  ↓ yes
re-read order
  ↓
is transition still valid?
  ↓ yes          ↓ no
retry (max 3)   return current state
```

This ensures **no update is silently lost**.

---

# 6. Update API Design

## 6.1 Internal Update Endpoint

```
PUT /orders/{orderId}/status
```

Request body:

```json
{
    "status": "PROCESSING",
    "vendorOrderId": "pi_3abc123",
    "metadata": "{\"gateway_ref\": \"ref_99\"}"
}
```

Response:

```json
{
    "id": "018f3c2d-b5f3-7c21-b7af-21ab9dfe1021",
    "status": "PROCESSING",
    "previousStatus": "PENDING",
    "version": 2,
    "updatedAt": "2026-04-05T15:00:00Z"
}
```

## 6.2 Vendor Webhook Endpoint

```
POST /webhooks/{vendor}
```

This is a separate controller because:

- Webhook payloads differ per vendor (Stripe sends different JSON than Razorpay).
- Authentication is different (webhook signature verification vs API auth tokens).
- We want to store the raw vendor payload in the audit log.

Flow:

```
receive webhook
  ↓
verify signature (vendor-specific)
  ↓
extract order_id from payload
  ↓
map vendor status → our internal status
  ↓
apply update (same service method as internal API)
  ↓
return 200 OK to vendor
```

### Critical: Always return 200 to vendor

Even if we fail to process the update, return `200` after persisting the raw webhook payload to a `webhook_inbox` table. This prevents the vendor from retrying endlessly. We process from the inbox asynchronously.

---

# 7. Webhook Inbox Pattern (Transactional Inbox)

To ensure webhook data is never lost, even during processing failures:

```sql
CREATE TABLE webhook_inbox (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    vendor TEXT NOT NULL,
    raw_payload JSONB NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',   -- PENDING, PROCESSED, FAILED
    attempts INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    processed_at TIMESTAMP,
    PRIMARY KEY(id)
);
```

Flow:

```
webhook arrives
  ↓
BEGIN TRANSACTION
  insert into webhook_inbox (raw_payload, vendor, status='PENDING')
COMMIT
  ↓
return 200 to vendor immediately
  ↓
async processor picks up PENDING entries
  ↓
processes the update (with optimistic locking + retry)
  ↓
marks inbox entry as PROCESSED
```

Benefits:

- Vendor never gets a non-200 response (prevents retry storms).
- If processing fails, the inbox entry stays `PENDING` and is retried by a scheduled job.
- Raw payload is preserved for debugging.

---

# 8. Duplicate / Idempotent Handling

Vendors often send the same webhook multiple times. We handle this at two levels:

## Level 1: Webhook Deduplication

Most vendors include a unique event ID in their payload. We store this in `webhook_inbox` and use a unique index:

```sql
ALTER TABLE webhook_inbox ADD COLUMN vendor_event_id TEXT;
CREATE UNIQUE INDEX idx_webhook_inbox_vendor_event ON webhook_inbox(vendor, vendor_event_id);
```

If a duplicate arrives, the insert fails with a unique constraint violation → return `200` without reprocessing.

## Level 2: State Transition Idempotency

If the order is already in the requested target state:

- Return success (200) without modifying anything.
- Do NOT create a new audit event (it's a duplicate).

Example: Webhook says "payment succeeded" but order is already `SUCCESS` → return 200, no-op.

---

# 9. Who Updates First? Priority & Ordering

## There is no priority — last valid write wins.

With optimistic locking, concurrent writers are serialized naturally:

```
Time 0ms: Webhook reads order (version=1, status=PROCESSING)
Time 1ms: API reads order (version=1, status=PROCESSING)
Time 2ms: Webhook writes (status=SUCCESS, version=2) → succeeds
Time 3ms: API writes (status=FAILED, version=1→expected) → OptimisticLockException
Time 4ms: API retries → re-reads (version=2, status=SUCCESS)
Time 5ms: API validates: SUCCESS→FAILED? → INVALID transition → returns 409
```

The state machine protects us. Even if a "late" write wins the optimistic lock race, the state transition matrix rejects illegal moves. The result is always consistent.

## Edge Case: Vendor webhook is "more authoritative"

If a genuine conflict arises (API says FAILED, webhook says SUCCESS at the same instant), the **state transition matrix** resolves it:

- If the first writer moves to SUCCESS, the second cannot move to FAILED (invalid transition).
- If the first writer moves to FAILED, the second can move to PENDING (retry), but NOT directly to SUCCESS.

The vendor webhook is always processed — it just might need to wait for the current state to allow the transition.

---

# 10. Class / Component Architecture

```
Controller Layer
├── OrderController          — handles PUT /orders/{id}/status
└── WebhookController        — handles POST /webhooks/{vendor}

Service Layer
├── OrderService             — core business logic
│   ├── updateOrderStatus()  — validates transition, applies update, writes audit
│   └── getOrder()           — read path
├── WebhookService           — webhook-specific logic
│   ├── receiveWebhook()     — stores to inbox, returns immediately
│   └── processWebhook()     — maps vendor payload → status update
└── OrderStateMachine        — pure function: validates state transitions

Repository Layer
├── OrderRepository          — JPA repository for orders
├── OrderEventRepository     — JPA repository for order_events
└── WebhookInboxRepository   — JPA repository for webhook_inbox

Domain
├── Order                    — JPA entity (updated in-place)
├── OrderEvent               — JPA entity (append-only audit)
├── WebhookInbox             — JPA entity (transactional inbox)
├── OrderStatus              — Enum of valid statuses
└── UpdateOrderRequest       — DTO for update API
```

---

# 11. Transaction Boundaries

## Update via API

```
@Transactional:
  1. Read order (SELECT with version)
  2. Validate state transition
  3. Update order fields + increment version (UPDATE ... WHERE version=?)
  4. Insert order_event audit row
  COMMIT or OptimisticLockException → retry
```

Both the order update and audit insert happen in the SAME transaction. Either both succeed or neither does.

## Update via Webhook

```
Transaction 1 (synchronous, in webhook handler):
  1. Insert into webhook_inbox
  COMMIT
  → return 200 to vendor

Transaction 2 (async processor):
  1. Read inbox entry
  2. Parse vendor payload
  3. Read order
  4. Validate state transition
  5. Update order + insert audit event
  6. Mark inbox entry as PROCESSED
  COMMIT
```

Two separate transactions ensures the vendor always gets a 200, and processing is retried independently.

---

# 12. Summary

| Concern                    | Solution                                          |
|----------------------------|---------------------------------------------------|
| Update or append?          | Update-in-place + append-only audit log           |
| Latest state?              | Always in `orders` table (single row)             |
| Concurrent updates?        | Optimistic locking with `@Version`                |
| Lost updates?              | Retry on `OptimisticLockException` (3 attempts)   |
| State validity?            | State transition matrix enforced in code          |
| Audit trail?               | `order_events` table (immutable append-only)      |
| Webhook reliability?       | Transactional inbox pattern                       |
| Duplicate webhooks?        | Vendor event ID deduplication + state idempotency |
| Who wins on conflict?      | State machine decides — not arrival order         |
| Webhook not lost on error? | Inbox persisted BEFORE processing begins          |

---

# 13. Implementation Order

Phase 1 (this sprint):

1. Add `version` and `updated_at` columns to `orders` entity
2. Create `OrderEvent` entity and `order_events` table
3. Create `OrderStatus` enum with transition validation
4. Create `OrderService` with `updateOrderStatus()`
5. Add `PUT /orders/{id}/status` endpoint
6. Add audit logging within the same transaction

Phase 2 (next sprint):

7. Create `WebhookInbox` entity and table
8. Create `WebhookController` and `WebhookService`
9. Implement async inbox processor (scheduled job)
10. Add vendor-specific payload mapping (Stripe, Razorpay, etc.)
11. Add webhook signature verification

Phase 3 (hardening):

12. Add retry mechanism for optimistic lock conflicts
13. Add dead-letter handling for permanently failed inbox entries
14. Add metrics (update latency, conflict rate, webhook processing lag)
