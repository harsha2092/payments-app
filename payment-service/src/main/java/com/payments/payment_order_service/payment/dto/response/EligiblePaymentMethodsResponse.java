package com.payments.payment_order_service.payment.dto.response;

import com.payments.payment_order_service.payment.business_models.PaymentMethodMetadata;

import java.util.List;

public class EligiblePaymentMethodsResponse {
    private List<PaymentMethodMetadata> supportedMethods;

    public EligiblePaymentMethodsResponse() {}

    public EligiblePaymentMethodsResponse(List<PaymentMethodMetadata> supportedMethods) {
        this.supportedMethods = supportedMethods;
    }

    public List<PaymentMethodMetadata> getSupportedMethods() {
        return supportedMethods;
    }

    public void setSupportedMethods(List<PaymentMethodMetadata> supportedMethods) {
        this.supportedMethods = supportedMethods;
    }
}
