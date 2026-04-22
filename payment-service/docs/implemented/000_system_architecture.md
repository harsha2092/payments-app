# Payment Order Service - System Architecture

Based strictly on the current source code implementation, the following architecture is established.

## 1. High-Level Architecture
The system is constructed as a **Modular Monolith** using Spring Boot and Java 21. It is structurally divided into distinct functional domains, primarily `order` and `payment`, with shared abstractions in the `common` package. 

Communication between domains is currently done via synchronous method calls (e.g., `PaymentService` calling `OrderService.recordPaymentSuccess`).

## 2. Package Structure & Domain Isolation

### `common` Package
- Contains shared infrastructures such as `AbstractEntity<ID>`.
- Implements the Spring Data `Persistable` interface to optimize Hibernate inserts. By using a transient `isNew` flag managed by `@PostLoad` and `@PostPersist` hooks, the system avoids generating an unnecessary `SELECT` statement before `INSERT` operations for entities with application-assigned IDs.

### `order` Package
- **Entities:** `PurchaseOrder`, `OrderLineItem`, `OrderStatus`.
- **Primary Responsibility:** Manages the lifecycle of an order and its constituent line items.
- **Workflow:** Computes total amounts across line items upon creation. Tracks `paidAmount` and transitions `OrderStatus` (e.g., `CREATED` -> `PARTIALLY_PAID` -> `PAID`) based on notifications from the payment domain.

### `payment` Package
- **Entities:** `PaymentAttempt`, `PaymentEvent`, `PaymentVendorLog`.
- **Primary Responsibility:** Handles the initialization, verification, and transition recording of payments against an order.
- **Rules Enforced:** 
  - Prevents payment amounts exceeding the order's remaining balance.
  - Ensures a maximum of one concurrent external payment attempt per order (excluding Internal Wallets).

## 3. Storage & Data Management
- **Primary Keys:** The application assigns IDs manually using Time-Ordered Epoch UUIDs (`UuidCreator.getTimeOrderedEpoch()`).
- **Optimistic Locking:** The `PaymentAttempt` entity employs `@Version` for optimistic locking, protecting against concurrent updates to payment statuses.
- **Audit & Logging:**
  - **PaymentEvent:** Acts as an append-only audit trail logging state transitions (e.g., `PENDING` -> `SUCCESS`).
  - **PaymentVendorLog (Hot/Cold Data Pattern):** Critical, strongly-typed "Hot" columns (like `vendorTransactionId`, `fundingSourceType`) are pulled into `PaymentAttempt`. The raw, verbose vendor JSON responses are treated as "Cold" data and stored separately into an append-only `PaymentVendorLog` table for debugging/archival, keeping `PaymentAttempt` lightweight.

## 4. Integration Patterns & Vendor Handling
A **"Zero-Trust Architecture"** is implemented for processing payment updates. The system does not explicitly trust client-side status updates.

- **GatewayClientFactory:** Uses a Strategy/Factory pattern to dynamically resolve the appropriate vendor integration client (`RazorpayClient`, `JuspayClient`, `InternalWalletClient`) based on the vendor string associated with the `PaymentAttempt`.
- **PaymentGatewayClient Interface:** Enforces a contract that returns a normalized `VerifiedPaymentResult`.
- **Verification Flow:** When a request is made to update a payment status, the `PaymentService` ignores unverified incoming statuses. Instead, it delegates verification to the specific vendor's client, decodes the authentic status payload, and enforces state machine rules (via `PaymentAttemptStatus.validateTransition`) before persisting changes and notifying the `order` domain.

## 5. Extensibility
The `GatewayClientFactory` ensures new vendors can be easily introduced by creating new classes implementing `PaymentGatewayClient` without deeply modifying the core `PaymentService` state logic.
