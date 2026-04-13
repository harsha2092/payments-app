# Payment Order Service - Local Setup & Overview

This document provides a summary of the current application state, the Java models and classes implemented, a high-level architecture overview, and instructions for running the application locally.

## 1. Architecture Overview
This Spring Boot application is the foundational layer for handling order storage, designed to subsequently scale into a partitioned and sharded PostgreSQL cluster.

### Current Implementation (V1)
- **Database:** PostgreSQL (running via Docker locally).
- **Table Schema:** A simple `orders` table (unpartitioned for now to ensure foundational data flow).
- **Primary Key:** `UUID` (specifically utilizing UUIDv7 through `uuid-creator` to encode time, preparing for future shard partition routing).
- **JSON Support:** The `metadata` field utilizes `JSONB` native to PostgreSQL to store unstructured vendor properties (handled natively via String mapping and Hibernate 6).

### Future Roadmap
- Introduction of timeframe-based (monthly) table partitioning.
- Dynamic DB shards per year based on UUIDv7 timestamp extraction.
- Abstract DB routing depending on the extracted timestamp.

---

## 2. Models and Classes (Modular Monolith)

The application is structured as a **Modular Monolith** to cleanly separate cart logic from money movement logic.

### **Module A: Order Management (`com.payments.payment_order_service.order`)**
Handles the user's cart, items, and checkout total.
- **`PurchaseOrder`**: The overarching checkout intent. Tracks `totalAmount` and `paidAmount`.
- **`OrderLineItem`**: Represents individual products tied to a `PurchaseOrder`.
- **`OrderController` / `OrderService`**: Exposes REST endpoints to create an order and compute total amounts.

### **Module B: Payment Execution (`com.payments.payment_order_service.payment`)**
Manages the actual interactions with payment gateways (Stripe, Razorpay, Wallets). It does not know what products the user bought.
- **`PaymentAttempt`**: A single transaction attempt for a specific `amount` against a `vendor` and `paymentMethod`.
- **`PaymentEvent`**: Read-only audit log tracking state changes of payment attempts.
- **`PaymentController` / `PaymentService`**: Exposes endpoints to initiate a payment attempt against an order, and a Webhook-like endpoint to update the attempt's status. Upon `SUCCESS`, triggers an inter-module update to increment the `paidAmount` on the parent `PurchaseOrder`.

---

## 3. Local Setup & Execution Steps

### Prerequisites
- **Java 21**
- **Docker / Colima** (used for local PostgreSQL hosting)

### Step 1: Start PostgreSQL server
Ensure your Docker daemon is running (e.g. `colima start` if using Colima on Mac), then run:
```bash
docker-compose up -d
```
*Note: We mapped the database locally to port `5433` (instead of 5432) to avoid conflicts with other local background databases.*

### Step 2: Reset the Database Schema
Since the application has evolved into a new hybrid order architecture, old tables (`orders`) must be cleared out.
```bash
docker-compose exec postgres psql -U payment_user -d payment_orders -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
```

### Step 3: Compile the Application
Generate the Maven dependencies, compile the latest code, and ensure tests pass:
```bash
./mvnw clean install
```

### Step 4: Run the Application
Start the Spring Boot REST Server:
```bash
./mvnw spring-boot:run
```

---

## 4. Testing Endpoints (Split Payment Flow)

Once running, you can test a split payment flow using standard `curl` commands.

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
