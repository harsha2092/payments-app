package com.payments.payment_order_service.payment.business_models;

import com.payments.payment_order_service.payment.entities.PaymentAttemptStatus;

public class VerifiedPaymentResult {
    private PaymentAttemptStatus status;
    private String vendorTransactionId;
    private String fundingSourceIdentifier;
    private String fundingSourceType;
    private String rawJsonPayload;

    public VerifiedPaymentResult(PaymentAttemptStatus status, String vendorTransactionId, String fundingSourceIdentifier, String fundingSourceType, String rawJsonPayload) {
        this.status = status;
        this.vendorTransactionId = vendorTransactionId;
        this.fundingSourceIdentifier = fundingSourceIdentifier;
        this.fundingSourceType = fundingSourceType;
        this.rawJsonPayload = rawJsonPayload;
    }

    public PaymentAttemptStatus getStatus() { return status; }
    public String getVendorTransactionId() { return vendorTransactionId; }
    public String getFundingSourceIdentifier() { return fundingSourceIdentifier; }
    public String getFundingSourceType() { return fundingSourceType; }
    public String getRawJsonPayload() { return rawJsonPayload; }
}
