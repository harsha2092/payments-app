package com.payments.payment_order_service.payment.helpers;

import com.payments.payment_order_service.payment.entities.PaymentAttemptStatus;

public class InvalidPaymentTransitionException extends RuntimeException {
    private final PaymentAttemptStatus fromStatus;
    private final PaymentAttemptStatus toStatus;

    public InvalidPaymentTransitionException(PaymentAttemptStatus fromStatus, PaymentAttemptStatus toStatus) {
        super("Invalid payment transition: " + fromStatus + " → " + toStatus);
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }

    public PaymentAttemptStatus getFromStatus() { return fromStatus; }
    public PaymentAttemptStatus getToStatus() { return toStatus; }
}
