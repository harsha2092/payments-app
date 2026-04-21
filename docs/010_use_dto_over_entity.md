# Why Use DTOs over Entities as Return Types

When building applications using Spring Boot and Hibernate / JPA, it is considered an industry best practice to return Data Transfer Objects (DTOs) from your Service layer to the Controller layer, rather than returning the raw JPA Entities directly. 

This document explains the technical reasons for adopting this pattern—particularly why failing to do so causes severe persistence and serialization issues.

## 1. Preventing `LazyInitializationException`

By default, Spring Boot has a property called `spring.jpa.open-in-view` enabled. This keeps the initial Hibernate session open for the entire duration of the web request, extending outside the `@Transactional` boundary of the service into the controller and JSON serialization phase. 

As a best practice (and for performance and database connection pooling reasons), this is typically set to `false` in production:
```properties
spring.jpa.open-in-view=false
```

When `spring.jpa.open-in-view` is heavily restricted or `false`:
1. The **Hibernate session is closed** the moment the `@Transactional` method in the Service layer finishes execution.
2. The Controller layer attempts to take the returned object and serialize it into JSON using Jackson.
3. When Jackson's serializer hits a lazily-loaded association getter (such as `order.getLineItems()`), it tries to transparently query the database to fetch the associated objects. 
4. Since the Session was already disconnected, Hibernate throws a critical `LazyInitializationException` and the API crashes.

**The Solution:**
DTOs are generated inside the transactional boundaries of the Service layer. Inside that method, the relationships are naturally loaded (or hydrated through a `@Query` `FETCH JOIN`), and mapped onto a flat, non-proxy DTO. When Jackson receives the DTO, it is serializing a Plain Old Java Object (POJO) with no proxy logic attached, meaning no external database queries will ever be stealthily requested.

## 2. Preventing Unintentional Data Exposure

Entities are direct 1:1 representations of backend database structures. If you serialize them directly to the user, you risk dumping sensitive columns in your JSON output.
Typical items you don't want the user to see:
- Password hashes / Salts
- Internal metadata (e.g. `version`, `isNew()`, soft delete flags like `is_deleted`)
- Audit timestamps used purely for tracing

DTOs act as an anti-corruption layer. Only properties explicitly written onto the DTO ever get mapped, providing whitelist security instead of trusting blacklist configurations like `@JsonIgnore` sprinkled everywhere on the entity class.

## 3. Decoupling API Contracts from Database Schema

Your API endpoints act as a rigid structural contract to external clients. If you expose your entity to the API view structure directly, you couple your database schema to your API contract.

- If you decide to rename a column or split a table, your API payload output automatically restructures itself unless carefully intercepted. This immediately breaks clients interacting with your system.
- Designing independent Response DTOs means you can dramatically overhaul your underlying entity and table mapping strategy independently of the JSON shape returned to your users. 

## 4. Solving Cartesian Products and Multiple Collection Loading

When writing queries traversing multiple collections (e.g., resolving `PurchaseOrder` -> `OrderLineItem` and `PurchaseOrder` -> `PaymentAttempt`), fetching everything from a single JPA query results in a chaotic cartesian product query producing M x N rows, introducing duplicate items in the Entity's embedded `List<>` attributes.

Since the DTO pattern requires custom mapping configuration, it creates an elegant space to safely discard duplicate relationships and apply business transformations, sorting logic, and formatting before handing a pristine dataset down to the controller layer.
