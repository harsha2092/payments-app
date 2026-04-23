package com.payments.payment_order_service.payment.entities;

public enum PaymentAttemptStatus {
    INITIATED,
    PENDING,
    SUCCESS,
    FAILED;

    public boolean canTransitionTo(PaymentAttemptStatus target) {
        if (this == target)
            return true; // idempotent
        if (this == INITIATED) {
            return target == PENDING || target == SUCCESS || target == FAILED;
        }
        if (this == PENDING) {
            return target == SUCCESS || target == FAILED;
        }
        return false; // Cannot transition out of SUCCESS or FAILED
    }

    public static void validateTransition(PaymentAttemptStatus from, PaymentAttemptStatus to) {
        if (!from.canTransitionTo(to)) {
            throw new com.payments.payment_order_service.payment.helpers.InvalidPaymentTransitionException(from, to);
        }
    }

    public static PaymentAttemptStatus mapFromGatewayStatus(String gatewayStatus) {
        if (gatewayStatus == null)
            return PENDING;
        return switch (gatewayStatus.toUpperCase()) {
            case "CREATED", "PENDING", "AUTHORIZED" -> PENDING;
            case "CAPTURED", "SUCCESS", "CHARGED" -> SUCCESS;
            case "FAILED", "REJECTED" -> FAILED;
            default -> PENDING;
        };
    }
}
