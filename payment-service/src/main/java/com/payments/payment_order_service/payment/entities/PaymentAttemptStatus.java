package com.payments.payment_order_service.payment.entities;

public enum PaymentAttemptStatus {
    PENDING,
    SUCCESS,
    FAILED;

    public boolean canTransitionTo(PaymentAttemptStatus target) {
        if (this == target) return true; // idempotent
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
}
