# Payments Application Mono-repo

This repository houses the microservices and modules necessary for the Payments application ecosystem. It is structured as a **Multi-Module Maven Repository** to ensure clear architectural boundaries while retaining the ease of a unified local developer experience.

## 1. Architecture Overview
This system is the foundational layer for handling order storage and downstream third-party payment routing. It is designed to scale dynamically into a partitioned and sharded PostgreSQL cluster.

### Current Implementation (V1)
- **Database:** PostgreSQL (running via Docker locally).
- **Table Schema:** A simple `orders` table (unpartitioned mapping for foundational data flow).
- **Primary Key:** `UUID` (specifically utilizing UUIDv7 through `uuid-creator` to encode time, preparing for future shard partition routing).
- **JSON Support:** Utilizes `JSONB` native to PostgreSQL to securely store unstructured vendor properties (handled natively via String mapping and Hibernate 6).

### Repository Modules
To separate concerns cleanly, this repository contains the following independent modules:

1. **[`payment-service`](payment-service/README.md):** 
   - The core modular monolith. Handles user carts, purchase orders, and payment tracking logic.
   - [Read the API Docs inside the module README](payment-service/README.md)
   
2. **[`mock-vendor-service`](mock-vendor-service/README.md):** 
   - An ephemeral, entirely isolated Spring Boot mock server using an in-memory H2 database. Used exclusively to simulate third-party payment gateways (Juspay, Razorpay) for local testing.
   - [Read the Mock Endpoints inside the module README](mock-vendor-service/README.md)

---

## 2. Local Setup & Execution Steps

### Prerequisites
- **Java 21**
- **Docker / Colima** (used for local PostgreSQL hosting)

### Step 1: Start PostgreSQL server
Ensure your Docker daemon is running (e.g. `colima start` if using Colima on Mac), then run from the root directory:
```bash
docker-compose up -d
```
*Note: The database is exposed locally on port `5433` to prevent conflicts with standard local Postgres instances.*

### Step 2: Reset the Database Schema
If needed, you can execute a hard reset on the local postgres tables by running:
```bash
docker-compose exec postgres psql -U payment_user -d payment_orders -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
```

### Step 3: Compile the Applications
Since this is a Maven Multi-Module Monorepo, running the `install` command from the repository root will compile both the `payment-service` and `mock-vendor-service` in a single pass:
```bash
./mvnw clean install
```

### Step 4: Run the Services
To test the full end-to-end checkout flow locally, you should run both services in separate terminal windows.

**Terminal 1: Start the Main Payment Order Service (Port 8080):**
```bash
./mvnw -pl payment-service spring-boot:run
```

**Terminal 2: Start the Mock Vendor Service (Port 8081):**
```bash
./mvnw -pl mock-vendor-service spring-boot:run
```
