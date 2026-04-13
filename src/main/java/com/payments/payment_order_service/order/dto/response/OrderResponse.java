package com.payments.payment_order_service.order.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.payments.payment_order_service.order.entities.PurchaseOrder;
import com.payments.payment_order_service.payment.entities.PaymentAttempt;

import java.util.List;

public class OrderResponse {

    @JsonUnwrapped
    private PurchaseOrder order;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<PaymentAttempt> paymentDetails;

    public OrderResponse(PurchaseOrder order, List<PaymentAttempt> paymentDetails) {
        this.order = order;
        this.paymentDetails = paymentDetails;
    }

    public PurchaseOrder getOrder() {
        return order;
    }

    public void setOrder(PurchaseOrder order) {
        this.order = order;
    }

    public List<PaymentAttempt> getPaymentDetails() {
        return paymentDetails;
    }

    public void setPaymentDetails(List<PaymentAttempt> paymentDetails) {
        this.paymentDetails = paymentDetails;
    }
}
