# Payment Order Service

This is the primary module responsible for handling checkout logic, tracking user orders, and orchestrating downstream payment attempts.

## Local Execution
Start this service independently by running the following from the repository root:
```bash
./mvnw -pl payment-service spring-boot:run
```
By default, the service binds to **Port 8080**.

---

## Testing Endpoints (Split Payment Flow)

Once running locally, you can test a full split-payment flow using standard `curl` commands.

### 1. Create a Purchase Order
Creates a checkout session with products inside. Focus on the `totalAmount` in the response.
```bash
curl -X POST http://localhost:8080/orders \
-H "Content-Type: application/json" \
-d '{
  "userId": "123e4567-e89b-12d3-a456-426614174000",
  "currency": "USD",
  "items": [
    {"productId": "prod_1", "productName": "Laptop", "quantity": 1, "unitPrice": 5000},
    {"productId": "prod_2", "productName": "Mouse", "quantity": 2, "unitPrice": 1000}
  ]
}'
```

### 2. Fetch Eligible Payment Methods
Use the `{orderId}` UUID returned from step 1 to retrieve the mocked wallet balance and allowed methods.
```bash
curl http://localhost:8080/orders/{PASTE_YOUR_ORDER_ID_HERE}/eligible-methods
```

### 3. Initiate a Payment Attempt
Use the `{orderId}` UUID. Notice we are attempting to pay a partial amount (e.g. 3000 out of 7000).
```bash
curl -X POST http://localhost:8080/orders/{PASTE_YOUR_ORDER_ID_HERE}/payments \
-H "Content-Type: application/json" \
-d '{
  "amount": 3000,
  "currency": "USD",
  "paymentMethod": "WALLET",
  "vendor": "INTERNAL"
}'
```

*(Note: Because this is a WALLET transaction, it will instantly evaluate to SUCCESS and immediately reflect in the Order!)*

### 4. Mark an External Payment as SUCCESS
If you initiated a non-wallet payment (like `CARD`), use the `{paymentAttemptId}` returned from Step 3 to simulate a successful vendor webhook.
```bash
curl -X PUT http://localhost:8080/payments/{PASTE_YOUR_PAYMENT_ATTEMPT_ID_HERE}/status \
-H "Content-Type: application/json" \
-d '{"status": "SUCCESS"}'
```

### 5. Verify the Order
If you fetch the parent order again, you will notice the `paidAmount` has incremented to reflect your payments, and the status automatically updated to `PARTIALLY_PAID` or `PAID`.
```bash
curl http://localhost:8080/orders/{PASTE_YOUR_ORDER_ID_HERE}
```

To fetch the order along with all its associated payment attempt records natively bundled in the response, you can append `?includePayments=true`:
```bash
curl "http://localhost:8080/orders/{PASTE_YOUR_ORDER_ID_HERE}?includePayments=true"
```
