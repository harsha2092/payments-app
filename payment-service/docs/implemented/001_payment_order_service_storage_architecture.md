
# Payment Order Service — Storage & Routing Architecture
## Technical Design Document (TDD)

Version: v1.0  
Scope: Order storage layer only  
Target scale: 10M → 10B orders  

---

# 1. Objectives

Design a storage architecture that supports:

- Millions → billions of orders
- Low-latency lookup by order ID
- Vendor callback lookups
- Partition pruning
- Horizontal scaling across DB nodes
- Archival strategy
- Future multi-region readiness
- Stateless Spring Boot services
- PostgreSQL as primary datastore

---

# 2. High-Level Architecture

Client  
↓  
Payment API Service  
↓  
Shard Router  
↓  
Shard-aware DataSource  
↓  
Correct Postgres shard  
↓  
Monthly partition  

Routing determined using:

UUIDv7 timestamp extraction

---

# 3. ID Strategy

## Decision

Use single-ID architecture:

public_id == private_id

Format:

ORD_<UUIDv7>

Example:

ORD_018f3c2d-b5f3-7c21-b7af-21ab9dfe1021

---

## Why UUIDv7

Advantages:

- Timestamp embedded
- Index-friendly ordering
- Partition routing hint
- Globally unique
- RFC-standard
- PostgreSQL native support
- Avoids locator-table dependency

---

# 4. Orders Table Schema

Parent partitioned table:

```sql
CREATE TABLE orders (

    id UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,

    user_id UUID NOT NULL,

    amount BIGINT NOT NULL,
    currency TEXT NOT NULL,

    payment_method TEXT NOT NULL,
    vendor TEXT,

    status TEXT NOT NULL,

    vendor_order_id TEXT,

    metadata JSONB,

    PRIMARY KEY(id, created_at)

) PARTITION BY RANGE(created_at);
```

---

# 5. Partition Strategy

Partition type:

RANGE(created_at)

Partition granularity:

Monthly

Example partitions:

orders_2026_01  
orders_2026_02  
orders_2026_03  

---

# 6. Partition Creation Strategy

Partitions created automatically:

- 3 months ahead
- Daily scheduler job

Example:

orders_2026_05  
orders_2026_06  
orders_2026_07  

Prevents runtime insert failures.

---

# 7. Lookup Strategy

Lookup flow:

receive order_id  
↓  
extract timestamp from UUIDv7  
↓  
compute partition window  
↓  
query partition  

Example query:

```sql
SELECT *
FROM orders
WHERE id = ?
AND created_at >= ?
AND created_at < ?
```

Triggers partition pruning.

---

# 8. Sharding Strategy

Introduce time-based shard routing.

Shard boundary:

Year-based

Example layout:

orders-db-2024  
orders-db-2025  
orders-db-2026  
orders-db-2027  

Each shard contains monthly partitions.

---

# 9. Shard Routing Logic

Routing rule:

extract year from UUIDv7 timestamp

Example:

UUID timestamp → 2026

Route to:

orders-db-2026

---

# 10. Spring Boot Shard Routing Implementation

Use:

AbstractRoutingDataSource

Routing key:

year(created_at)

Datasource registry:

Map<Integer, DataSource>

Example:

2024 → datasourceA  
2025 → datasourceB  
2026 → datasourceC  

---

# 11. Read/Write Split Strategy

Each shard contains:

primary DB  
read replica  

Routing:

write → primary  
read → replica  

Exception:

read-after-write requests must hit primary.

---

# 12. Index Strategy

Primary:

```sql
PRIMARY KEY(id, created_at);
```

Secondary:

```sql
CREATE INDEX idx_orders_user
ON orders(user_id);
```

Vendor lookup:

```sql
CREATE INDEX idx_vendor_order
ON orders(vendor_order_id);
```

Status filtering:

```sql
CREATE INDEX idx_orders_status
ON orders(status);
```

---

# 13. Vendor Callback Lookup Strategy

Webhook payload contains:

order_id

Lookup flow:

decode timestamp  
↓  
route shard  
↓  
route partition  
↓  
update order  

Single-partition update.

---

# 14. Partition Pruning Strategy

Triggered when query includes:

created_at range condition

Example:

```sql
WHERE id = ?
AND created_at >= partition_start
AND created_at < partition_end
```

Ensures single-partition scan.

---

# 15. Archival Strategy

Old partitions moved to:

cold storage cluster

Example:

orders-db-2022 → archive cluster

Migration method:

detach partition  
attach archive DB  

No downtime required.

---

# 16. Data Retention Strategy

Example lifecycle:

0–2 years → hot cluster  
2–5 years → warm cluster  
5+ years → cold archive  

Controlled via partition movement.

---

# 17. Expected Scale Envelope

Per shard:

~1B rows supported

Cluster capacity:

10 shards → 10B rows

Horizontally expandable.

---

# 18. Future Scaling Options

Optional upgrades:

- Redis lookup cache
- Kafka CDC pipeline
- Citus distributed Postgres
- Multi-region active-active shards

None required initially.

---

# 19. Failure Isolation Model

Shard-level failures isolated.

Example:

orders-db-2026 outage

Impact:

only 2026 orders affected

Other shards unaffected.

---

# 20. Observability Requirements

Metrics per shard:

Track:

- insert latency
- partition size
- replica lag
- index hit ratio
- vacuum progress

Expose via:

Prometheus  
Grafana  

---

# 21. Schema Migration Strategy

Partition-safe migrations:

Use expand-only migrations.

Example:

add nullable column  
backfill async  
enforce constraint later  

Never perform blocking table rewrites.

---

# 22. Locator Table Strategy

Locator table avoided initially because:

UUIDv7 enables timestamp extraction.

Therefore:

direct shard routing  
direct partition routing  

Locator table becomes optional optimization beyond:

5B+ rows per shard

---

# 23. Final Architecture Summary

System uses:

UUIDv7 primary key  
timestamp-based shard routing  
monthly partitions per shard  
read replicas per shard  
partition pruning queries  
prefix-based public IDs  
no locator table initially  

Scales cleanly to:

10B+ orders
