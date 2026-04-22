# Third-Party Vendor Mock Service

## 1. Objective
Currently, the Payment Order Service does not integrate with an actual external payment vendor like Juspay, Razorpay, or Stripe. To facilitate end-to-end testing, local development, and to validate the `PaymentGatewayClient` abstraction, we need a Mock Vendor Service.

This service will emulate the behavior of a real payment vendor by allowing the Payment Order Service to create external orders, retrieve simulated payment statuses, and query transaction logs.

## 2. Architecture & Deployment

- **Repository Structure:** Multi-Module Monorepo. The mock service will be implemented as a completely separate Maven/Gradle module alongside the main `payments-app`. This ensures strict boundary isolation while keeping local setup frictionless. Crucially, it allows us to build the production JAR exclusively from the main app, completely ignoring the mock module and its dependencies in production.
- **Technology Stack:** Spring Boot (to maintain tech stack consistency with the main application).
- **Database:** File-based H2 database (`jdbc:h2:file:./data/...`) for local deployments to ensure local order states persist across application restarts, with a simple schema dedicated strictly to the mock service.
- **Deployment:** The mock service can be run locally alongside the main Payment Service, or pushed to a staging environment as a separate, isolated container.

## 3. Data Model

The Mock Vendor Service will maintain a simplified internal state of orders using a `mock_orders` table.

```sql
CREATE TABLE mock_orders (
    id VARCHAR(50) PRIMARY KEY,          -- e.g., mock_ord_12345ABC
    amount DECIMAL(10, 2) NOT NULL,
    currency VARCHAR(3) DEFAULT 'USD',
    status VARCHAR(20) NOT NULL,         -- CREATED, AUTHORIZED, CAPTURED, FAILED
    payment_method VARCHAR(20),          -- UPI, CARD, NETBANKING (set during simulated payment completion)
    vendor_payment_id VARCHAR(50),       -- Unique transaction mapping ID
    metadata JSONB,                      -- Free-flowing JSON data for vendor-specific properties
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

## 4. Exposed API Endpoints

The mock service will expose the following REST endpoints to emulate standard Gateway integrations.

### 4.1. Create Order
Emulates the `POST /orders` call to a vendor to initialize a payment intent.

- **Endpoint:** `POST /mock-vendor/orders`
- **Request Body:**
  ```json
  {
    "amount": 2100.00,
    "currency": "INR",
    "receipt": "order_123"
  }
  ```
- **Response (200 OK):**
  ```json
  {
    "id": "mock_ord_9A8B7C6D",
    "amount": 2100.00,
    "currency": "INR",
    "status": "CREATED",
    "created_at": "2026-04-20T10:00:00Z"
  }
  ```

### 4.2. Get Order Status
Emulates the status verification call `GET /orders/{id}` when the frontend signals a terminal state.

- **Endpoint:** `GET /mock-vendor/orders/{id}`
- **Response (200 OK):**
  ```json
  {
    "id": "mock_ord_9A8B7C6D",
    "amount": 2100.00,
    "currency": "INR",
    "status": "CAPTURED",
    "payment_method": "CARD",
    "vendor_payment_id": "pay_XYZ123",
    "metadata": {
       "bank": "HDFC",
       "last_four": "4242"
    }
  }
  ```

### 4.3. Simulate Payment Completion (Test-Only Utility)
Since there is no actual frontend widget to enter credit card details in a mock, we provide a unique utility endpoint. The testing script or test UI calls this endpoint to move a `CREATED` order to a terminal state (`CAPTURED` or `FAILED`).

- **Endpoint:** `PUT /mock-vendor/orders/{id}/simulate`
- **Request Body:**
  ```json
  {
    "target_status": "CAPTURED",
    "payment_method": "UPI",
    "simulate_webhook": true 
  }
  ```
- **Behavior:**
  1. Updates the `status` of `mock_ord_9A8B7C6D` to `CAPTURED`.
  2. Generates dummy values for `vendor_payment_id` and specific payment method metadata.
  3. *(Optional)* If `simulate_webhook` is true, it asynchronously fires a simulated webhook event back to the Payment Order Service's webhook ingestion endpoint.

## 5. Integration with Payment Order Service

Within the `payment_order_service`, we will implement a new strategy in our Gateway Abstraction (as proposed in `005_vendor_specific_handling.md`).

```java
@Service
public class MockVendorClient implements PaymentGatewayClient {
    
    @Override
    public boolean supports(String vendor) {
        return "MOCK".equalsIgnoreCase(vendor);
    }
    
    @Override
    public VerifiedPaymentResult verifyPaymentStatus(PaymentAttempt attempt) {
        // Fire REST API call over HTTP to: GET http://localhost:8081/mock-vendor/orders/{vendorTransactionId}
        MockOrderResponse response = restTemplate.getForObject(
            mockConfig.getBaseUrl() + "/mock-vendor/orders/" + attempt.getVendorTransactionId(),
            MockOrderResponse.class
        );
        
        // Map raw response to standard Hot Path Columns
        return mapToVerifiedResult(response);
    }
}
```

## 6. Development Phasing

1. **Phase 1:** Spin up the Mock Vendor Service as a basic runnable module.
2. **Phase 2:** Implement the endpoints (`POST /orders` and `GET /orders/{id}`).
3. **Phase 3:** Integrate the `MockVendorClient` in the `payment_order_service` and use it exclusively in local development (`spring.profiles.active=local`).
4. **Phase 4:** Add the Simulation Endpoint (`PUT /simulate`) and wire up webhooks for async testing.
