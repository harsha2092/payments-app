package com.payments.payment_order_service.payment.clients;

import com.payments.payment_order_service.payment.business_models.VerifiedPaymentResult;
import com.payments.payment_order_service.payment.entities.PaymentAttempt;
import com.payments.payment_order_service.payment.entities.PaymentAttemptStatus;
import org.springframework.stereotype.Component;

@Component
public class JuspayClient implements PaymentGatewayClient {

    @Override
    public boolean supports(String vendor) {
        return "JUSPAY".equalsIgnoreCase(vendor);
    }

    @Override
    public VerifiedPaymentResult verifyPaymentStatus(PaymentAttempt attempt) {
        // MOCK: Represents real backend HTTP call to api.juspay.in/orders
        String mockJson = "{ \"status\": \"CHARGED\", \"payment_method_type\": \"CARD\", \"card\": { \"last_four_digits\": \"1234\", \"card_type\": \"CREDIT\" } }";

        return new VerifiedPaymentResult(
                PaymentAttemptStatus.SUCCESS,
                "merchant_success-JP123-1",
                "1234",
                "CREDIT",
                mockJson
        );
    }
}
