# Architecture Proposal: Modular Monolith (Orders + Payments)

To support both **Order Line Items** and **Split Payments/Retries** holistically, we will evolve the application into a **Modular Monolith**. 

We will create two distinct domains (packages) within the same Spring Boot app. They will interact cleanly without tangling their database tables or logic.

---

## 1. Domain Separation

### Module A: Order Management (`com.payments.payment_order_service.order`)
*Responsibility:* Manages the user's cart, items, and checkout total.

**Entities:**
1.  **`Order`** (The overarching checkout intent)
    *   `id` (UUID)
    *   `userId` (UUID)
    *   `totalAmount` (Long)
    *   `paidAmount` (Long) - Tracks how much has been successfully paid across attempts.
    *   `currency` (String)
    *   `status` (`CREATED`, `PARTIALLY_PAID`, `PAID`, `CANCELLED`)
    *   `createdAt`, `updatedAt`
2.  **`OrderLineItem`** (One-to-Many with `Order`)
    *   `id` (UUID)
    *   `order` (ManyToOne relation to Order)
    *   `productId` (String)
    *   `productName` (String)
    *   `quantity` (Integer)
    *   `unitPrice` (Long)
    *   `totalPrice` (Long)

### Module B: Payment Execution (`com.payments.payment_order_service.payment`)
*Responsibility:* Manages the actual interactions with payment gateways (Stripe, Razorpay, Wallets). It does not know what products the user bought.

**Entities:**
1.  **`PaymentAttempt`** (Formerly the old `Order` entity)
    *   `id` (UUID)
    *   `orderId` (UUID) - *Loose reference* to Module A's Order. Not a direct foreign key JPA relationship to keep modules decoupled.
    *   `amount` (Long) - The amount being attempted *in this specific transaction*.
    *   `currency` (String)
    *   `paymentMethod` (`UPI`, `CARD`, `WALLET`)
    *   `vendor` (`STripe`, `Razorpay`)
    *   `vendorTransactionId` (String)
    *   `status` (`PENDING`, `SUCCESS`, `FAILED`)
    *   `version` (Long) - Optimistic Locking.
    *   `createdAt`, `updatedAt`
2.  **`PaymentEvent`** (Formerly `OrderEvent` audit log)
    *   *Appends a record every time a `PaymentAttempt` changes state.*

---

## 2. The Integrated Flow

How split payments and retries will work across modules:

1.  **Create Order & Items (Module A):**
    *   `POST /orders` payload contains items: `[{"productId": "prod_1", "quantity": 2, "price": 1000}]`
    *   Creates `Order` (totalAmount: 2000, paidAmount: 0, status: CREATED) and `OrderLineItem`s.
2.  **Initiate Payment (Module B):**
    *   `POST /orders/{orderId}/payments` payload: `{"amount": 1000, "paymentMethod": "WALLET", "vendor": "INTERNAL"}`
    *   Creates `PaymentAttempt 1` (amount: 1000, status: PENDING).
3.  **Payment Succeeds (Module B -> Module A):**
    *   `PUT /payments/{paymentId}/status` payload: `{"status": "SUCCESS"}`
    *   `PaymentAttempt 1` updates to `SUCCESS`. Audit `PaymentEvent` created.
    *   **Cross-Module Communication:** `PaymentService` triggers an event to `OrderService` (e.g., via simple method call or Spring ApplicationEvent): *"Payment of 1000 succeeded for Order X"*.
    *   `OrderService` updates `Order` (paidAmount: 1000). Status becomes `PARTIALLY_PAID`.
4.  **Second Payment (User pays rest with card):**
    *   `POST /orders/{orderId}/payments` payload: `{"amount": 1000, "method": "CARD"}`
    *   Creates `PaymentAttempt 2`.
    *   Upon success, `OrderService` is notified again. `paidAmount` becomes 2000. Since `paidAmount == totalAmount`, Order status becomes `PAID`.

If a `PaymentAttempt` fails, it simply sits in `FAILED` state. The user can just initiate a new `POST /orders/{orderId}/payments` attempt. The `Order` remains unaffected until an attempt succeeds.

---

## 3. Implementation Plan

1.  **Stop Existing Service:** Clean the database (`orders`, `order_events`) entirely since the schema is changing fundamentally.
2.  **Create Packages:** Move existing code into `...payment` package and rename them (`Order` -> `PaymentAttempt`, `OrderEvent` -> `PaymentEvent` etc). Adjust fields.
3.  **Build Order Module:** Create `Order` and `OrderLineItem` entities and `OrderService` in the `...order` package.
4.  **Define Integration Point:** Wire `PaymentService` to notify `OrderService` upon a successful `PaymentAttempt` to increment the `paidAmount`.
5.  **Refactor Controllers:**
    *   `POST /orders` (Creates Order + Line Items)
    *   `POST /orders/{id}/payments` (Creates a PaymentAttempt)
    *   `PUT /payments/{paymentId}/status` (Updates PaymentAttempt status with optimistic locking)

Please review this modular monolith design. If you approve, I will begin the sweeping refactor to implement this structure.
