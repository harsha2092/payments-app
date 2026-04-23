package com.payments.payment_order_service.payment.clients;

import com.payments.payment_order_service.payment.business_models.GatewayOrderRequest;
import com.payments.payment_order_service.payment.business_models.GatewayOrderResponse;
import com.payments.payment_order_service.payment.business_models.VerifiedPaymentResult;
import com.payments.payment_order_service.payment.entities.PaymentAttempt;

public interface PaymentGatewayClient {
    GatewayOrderResponse createOrder(GatewayOrderRequest request);
    VerifiedPaymentResult verifyPaymentStatus(PaymentAttempt attempt);
    boolean supports(String vendor);
}
