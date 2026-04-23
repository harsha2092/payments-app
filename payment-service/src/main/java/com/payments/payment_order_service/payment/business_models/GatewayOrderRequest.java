package com.payments.payment_order_service.payment.business_models;

import java.util.UUID;

public class GatewayOrderRequest {
    private UUID paymentAttemptId;
    private Long amount;
    private String currency;

    public GatewayOrderRequest(UUID paymentAttemptId, Long amount, String currency) {
        this.paymentAttemptId = paymentAttemptId;
        this.amount = amount;
        this.currency = currency;
    }

    public UUID getPaymentAttemptId() {
        return paymentAttemptId;
    }

    public void setPaymentAttemptId(UUID paymentAttemptId) {
        this.paymentAttemptId = paymentAttemptId;
    }

    public Long getAmount() {
        return amount;
    }

    public void setAmount(Long amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }
}
