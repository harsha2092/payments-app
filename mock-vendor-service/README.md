# Mock Vendor Service

This module is an isolated, ephemeral Spring Boot application designed specifically to emulate third-party payment gateways (like Juspay or Razorpay) for local testing and integration environments.

It uses an in-memory H2 database, meaning its state resets completely every time the application restarts.

## Local Execution

Start the service independently by running this from the repository root:
```bash
./mvnw -pl mock-vendor-service spring-boot:run
```
By default, the service binds to **Port 8081** to avoid conflicting with the main Payment Order Service.

---

## Testing Endpoints

You can interact with the mock vendor service directly using the following `curl` commands to simulate vendor checkout verification flows.

### 1. Create a Payment Intent (Order)
Emulates the `POST /orders` call a client typically makes to initialize a payment intent with the external vendor.

```bash
curl -X POST http://localhost:8081/mock-vendor/orders \
-H "Content-Type: application/json" \
-d '{
  "amount": 2100.00,
  "currency": "INR",
  "receipt": "order_123"
}'
```

### 2. Fetch Order Status
Emulates the status verification call `GET /orders/{id}` made securely by the backend to verify the true payment success. 

*(Use the `"id"` string returned from the create call, e.g., `mock_ord_...`)*

```bash
curl http://localhost:8081/mock-vendor/orders/{PASTE_MOCK_ORDER_ID_HERE}
```

### 3. Simulate Payment Completion
Since there is no frontend checkout widget to physically enter credit card details in a mock environment, we provide a test-only utility endpoint. Hitting this endpoint bypasses the UI and moves a `CREATED` order straight to a terminal state (`CAPTURED` or `FAILED`).

```bash
curl -X PUT http://localhost:8081/mock-vendor/orders/{PASTE_MOCK_ORDER_ID_HERE}/simulate \
-H "Content-Type: application/json" \
-d '{
  "targetStatus": "CAPTURED",
  "paymentMethod": "UPI",
  "simulateWebhook": false,
  "metadata": {
    "bank": "HDFC",
    "last_four": "4242",
    "transaction_time": "2026-04-23T10:00:00Z"
  }
}'
```
*Tip: Run the **Fetch Order Status** command again after executing this simulation to verify that the mock order has updated its database row successfully!*
