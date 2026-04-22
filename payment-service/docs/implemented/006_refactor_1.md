# Refactoring Plan for Order and Payment Packages

This plan addresses the restructuring of the `order` and `payment` packages to follow industry-standard Spring Boot application architecture, resolving the current "cluttered" flat structure. The new structure will organize code into clear boundaries based on responsibilities.

## Proposed Changes

We will group the existing flat files into the following standardized sub-packages:
- `controllers`
- `dto.request` and `dto.response`
- `entities`
- `repositories`
- `service`
- `helpers` (for utilities, exceptions, factories)
- `business_models` (for domain models, gateway clients, etc.)

---

### Order Package (`com.payments.payment_order_service.order`)

The package will be broken down as follows:

- **`controllers`**
  - [NEW] `OrderController.java`
- **`dto/request`**
  - [NEW] `CreateOrderRequest.java`
- **`entities`**
  - [NEW] `PurchaseOrder.java`
  - [NEW] `OrderLineItem.java`
  - [NEW] `OrderStatus.java`
- **`repositories`**
  - [NEW] `OrderRepository.java`
- **`service`**
  - [NEW] `OrderService.java`

---

### Payment Package (`com.payments.payment_order_service.payment`)

The package will be broken down as follows:

- **`controllers`**
  - [NEW] `PaymentController.java`
- **`dto/request`**
  - [NEW] `CreatePaymentRequest.java`
  - [NEW] `UpdatePaymentStatusRequest.java`
- **`dto/response`**
  - [NEW] `EligiblePaymentMethodsResponse.java`
- **`entities`**
  - [NEW] `PaymentAttempt.java`
  - [NEW] `PaymentVendorLog.java`
  - [NEW] `PaymentEvent.java`
  - [NEW] `PaymentAttemptStatus.java`
- **`repositories`**
  - [NEW] `PaymentAttemptRepository.java`
  - [NEW] `PaymentVendorLogRepository.java`
  - [NEW] `PaymentEventRepository.java`
- **`service`**
  - [NEW] `PaymentService.java`
- **`helpers`**
  - [NEW] `InvalidPaymentTransitionException.java`
- **`business_models`**
  - [NEW] `VerifiedPaymentResult.java`
  - [NEW] `PaymentMethodMetadata.java`
- **`clients`**
  - [NEW] `GatewayClientFactory.java`
  - [NEW] `PaymentGatewayClient.java`
  - [NEW] `JuspayClient.java`
  - [NEW] `RazorpayClient.java`
  - [NEW] `InternalWalletClient.java`

## Implementation Steps

1. Create the new sub-directories inside `order/` and `payment/`.
2. Move the files to their respective destinations.
3. Update all `package com.payments...` declarations across all moved files.
4. Update all corresponding `import` statements across the entire project (including the `GlobalExceptionHandler` and test files, if any) to point locally to the new package paths.

## User Review Required

> [!CAUTION]
> Because there are over 25 files being moved and updated, I plan to run standard `bash` commands to create directories and move files, and then execute consecutive tool calls to update the file contents safely. 

> [!IMPORTANT]
> The `repositories` folder isn't explicitly in your original list but is a standard Spring Boot convention. I've placed the repositories there. The API clients and DTOs have been separated similarly. 

Are you satisfied with this package mapping? Once you approve, I'll proceed with executing this cleanly and step-by-step.
