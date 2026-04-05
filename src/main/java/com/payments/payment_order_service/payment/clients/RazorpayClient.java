package com.payments.payment_order_service.payment.clients;

import com.payments.payment_order_service.payment.business_models.VerifiedPaymentResult;
import com.payments.payment_order_service.payment.entities.PaymentAttempt;
import com.payments.payment_order_service.payment.entities.PaymentAttemptStatus;
import org.springframework.stereotype.Component;

@Component
public class RazorpayClient implements PaymentGatewayClient {

    @Override
    public boolean supports(String vendor) {
        return "RAZORPAY".equalsIgnoreCase(vendor);
    }

    @Override
    public VerifiedPaymentResult verifyPaymentStatus(PaymentAttempt attempt) {
        // MOCK: Represents real backend HTTP call to api.razorpay.com/v1/payments
        String mockJson = "{ \"id\": \"pay_MT48CvBhIC98MQ\", \"status\": \"captured\", \"method\": \"netbanking\", \"bank\": \"ICICI\" }";

        return new VerifiedPaymentResult(
                PaymentAttemptStatus.SUCCESS,
                "pay_MT48CvBhIC98MQ",
                "ICICI",
                null,
                mockJson
        );
    }
}
