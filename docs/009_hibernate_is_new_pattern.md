# Hibernate `isNew` Optimization Pattern

## The Problem
In Spring Data JPA / Hibernate, the `CrudRepository.save(entity)` method behaves differently depending on whether it thinks the given entity is "new" or "existing":
- **New Entity**: Hibernate fires an `INSERT` statement immediately.
- **Existing Entity**: Hibernate typically issues a `SELECT` query to see if the entity exists in the database. If it exists, it issues an `UPDATE`; if not, it issues an `INSERT`.

By default, Hibernate determines an entity's state by inspecting its `@Id` field:
- If the (`@Id`) field is `null` (or `0` for primitive numerical values) and uses a `@GeneratedValue`, Hibernate knows the entity is strictly **new**.
- However, if the ID is generated *within our application code* (e.g., setting a `UUID` randomly via `UuidCreator` before persisting), Hibernate will observe that the ID is **not null**. As a result, it assumes it might be merging a detached entity and triggers an extra `SELECT` statement before proceeding with the `INSERT`.

This results in a major performance degradation during high-throughput insertion scenarios, as every `save()` translates to `1 SELECT + 1 INSERT` rather than a single `INSERT`.

## The Solution: Implementing `Persistable<ID>`
To circumvent this extraneous `SELECT` query, we bypass Hibernate's default ID-checking behavior by implementing the Spring Data `Persistable<ID>` interface in a reusable `@MappedSuperclass`. 

When an entity implements `Persistable`, Hibernate relies entirely on the custom `isNew()` method rather than inspecting the ID field itself.

### Our Implementation pattern
We created `AbstractEntity<ID>` that manages a transient (non-persistent) boolean flag:

```java
package com.payments.payment_order_service.common.entities;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

@MappedSuperclass
public abstract class AbstractEntity<ID> implements Persistable<ID> {

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
```

### How It Works:
1. **Creation**: When a new entity instance is created via `new Entity()`, the `isNew` flag defaults to `true`.
2. **First Save**: We set our pre-generated `UUID`. Upon `save()`, Hibernate checks `isNew()`, which returns `true`. It instantly runs the `INSERT` statement, skipping the costly `SELECT`.
3. **Lifecycle Callbacks**: 
    - `@PostPersist`: Right after the entity is saved to the database.
    - `@PostLoad`: Right after the entity is fetched/read from the database by Hibernate.
    In both of these JPA lifecycle events, the `markNotNew()` method explicitly toggles the `isNew` flag to `false`. Any further modifications and `save()` calls will be correctly interpreted as an `UPDATE`.

### Which entities are using this?
This pattern has been enforced on all entities deriving their Primary Keys strictly from application-layer generation logic:
- `PurchaseOrder`
- `OrderLineItem`
- `PaymentAttempt`
- `PaymentVendorLog`

We excluded entities such as `PaymentEvent` from this pattern since their identifiers are generated natively by Hibernate using `@GeneratedValue(strategy = GenerationType.UUID)`, which natively avoids the N+1 select problem.
