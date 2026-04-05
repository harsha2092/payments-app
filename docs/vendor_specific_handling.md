# Vendor-Specific Handling & Status Verification Architecture

## 1. Core Philosophy: Zero-Trust Frontend Execution
Currently, the application allows the client to manually declare a payment as successful via the `PUT /payments/{paymentId}/status` API. This is fundamentally insecure and vulnerable to payload tampering. 

**New Rule:** The frontend's `PUT /status` (or any generic webhook) serves merely as a *trigger signal*. The backend must **never** blindly trust the provided payload. Instead, upon receiving the signal, the backend will construct a server-to-server call to the specific vendor to authenticate the terminal state before writing to the database.

## 2. Abstraction Strategy: The Gateway Factory Pattern
Because each vendor (Juspay, Razorpay, Stripe, Internal Wallet) possesses completely orthogonal API signatures, we will implement a unified **Gateway Client Abstraction**.

### 2.1 The Interface
```java
public interface PaymentGatewayClient {
    // Queries the respective vendor and returns the verified terminal status
    VerifiedPaymentResult verifyPaymentStatus(PaymentAttempt attempt);
    boolean supports(String vendor);
}
```

### 2.2 Implementations (Strategy Pattern)
- **`JuspayClient`**: Constructs an HTTP GET to `api.juspay.in/orders/{id}` using the backend's secure API key. Parses `status: CHARGED`.
- **`RazorpayClient`**: Constructs an HTTP GET to `api.razorpay.com/v1/payments/{id}`. Parses `status: captured`.
- **`InternalWalletClient`**: If `vendor == "INTERNAL"`, executes a synchronous local call or internal REST call to `GET /internal/wallets/transactions/{txnId}` to verify the ledger lock.

*Execution*: The `PaymentService` relies on a `GatewayClientFactory` that iterates through the beans and delegates the verification securely.

## 3. Storage Strategy: The Hot/Cold Hybrid Pattern
Juspay returns deeply nested card data (`card.card_issuer`, `payment_method_type`), whereas Razorpay returns flat fields (`bank`, `method`, `vpa`). 

**Performance & Schema Challenge:** 
If we create dedicated columns for every vendor-specific edge case, the schema breaks relational standards. However, if we lazily dump *everything* into a `JSONB` column, high-throughput queries (like fetching orders to render the user's dashboard) will suffer severe performance penalties due to PostgreSQL's TOAST extraction and path parsing.

**Solution: Hybrid Hot/Cold Extraction**
We will physically bifurcate the data into native relational columns ("Hot" path) and a JSONB archival column ("Cold" path).

### 3.1 Hot Path (Native SQL Columns)
Any field required to render the application UI *frequently* or execute immediate core business logic must be explicitly mapped to native `PaymentAttempt` columns. The `PaymentGatewayClient` normalizes the radically different vendor JSONs into standardized columns:
- `vendor_transaction_id`: The ID required to query the external gateway again (e.g., `ordeh_57df...` from Juspay, or `pay_MT48...` from Razorpay).
- `funding_source_identifier`: A generalized string used for UI display.
  - If `paymentMethod == UPI`: Stores the `vpa`.
  - If `paymentMethod == NETBANKING`: Stores the `bank` (e.g. `ICIC`).
  - If `paymentMethod == CARD`: Stores the `last_four_digits`.
- `funding_source_type`: A generalized string indicating type (e.g., `CREDIT`, `DEBIT` for cards).

### 3.2 Cold Path (Externalized JSONB Archival)
While PostgreSQL natively offloads huge JSON blobs into hidden background TOAST tables, **we will store the raw vendor payload in a mathematically separate logging table** (e.g., `payment_vendor_logs`) rather than attaching it to the `PaymentAttempt` entity.

**Why a separate table?**
1. **JVM Memory Protection**: It eliminates the risk of Hibernate accidentally fetching and parsing massive JSON strings into the application memory pool during standard, high-volume DB scans (`SELECT *`).
2. **Multi-Step Audit Trail**: A single payment attempt often produces *multiple* discrete vendor payloads (e.g., *Webhook: Intent Authorized*, followed by *Webhook: Funds Captured*). Constantly overwriting one JSONB column destroys data. An append-only log table perfectly accommodates chronological events.

```java
@Entity
public class PaymentVendorLog {
    @Id
    private UUID id;
    private UUID paymentAttemptId; // Foreign Key index
    private String eventType;      // e.g. "INTENT_CREATED", "WEBHOOK_CAPTURED"

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String vendorMetadata; // Store the raw JSON securely
    
    private LocalDateTime createdAt;
}
```
- We **never** `JOIN` or fetch the `PaymentVendorLog` table on the hot path checkout routes. It acts solely as an append-only archive.

### 3.3 Handling Future Requirements
If the business suddenly requires querying a previously "Cold" JSON field frequently (e.g., `refunds` array):
1. **Migration**: We introduce a native column (e.g., `amount_refunded`).
2. **Backfill**: We write a one-time async script to pull the numerical value out of the historic `JSONB` blobs into the native column.
3. **Application API**: The `GatewayClient` is trivially updated to map that value into the Hot Path natively going forward.

## 4. Execution Flow Refactor
**Current Flow:** Client -> `PUT /status { status: "SUCCESS" }` -> Database `SUCCESS`.

**New Flow Flow:**
1. Client -> `PUT /status`. (Payload simply says: "Hey, I finished the redirect/3DS flow").
2. `PaymentController` calls `PaymentService.updatePaymentStatus(paymentId)`.
3. `PaymentService` injects the `PaymentAttempt` into the `GatewayClientFactory`.
4. The resolved Gateway Client (e.g., Razorpay) calls Razorpay servers.
5. Razorpay Server responds securely: `{ status: "captured", amount: 2100, bank: "ICICI" }`.
6. `PaymentService` confirms the secure payload `amount` matches the intent `amount`.
7. `PaymentService` updates the attempt status to `SUCCESS`.
8. `PaymentService` dumps the entire Razorpay JSON string into the `vendorMetadata` JSONB column via Hibernate.
9. Cross-module event is fired to `OrderService` exactly as before.

## 5. Webhook Handling Readiness
This same exact `verifyPaymentStatus` abstraction is 100% reusable for asynchronous webhooks. If Razorpay fires a webhook to `POST /webhooks/razorpay`, we do not parse the webhook body blindly. We take the ID from the webhook, push it into the `RazorpayClient.verifyPaymentStatus()` and pull the state authentically.
