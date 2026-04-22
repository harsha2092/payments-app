package com.payments.payment_order_service.payment.dto.request;

public class UpdatePaymentStatusRequest {
    private String status;
    private String vendorTransactionId;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getVendorTransactionId() { return vendorTransactionId; }
    public void setVendorTransactionId(String vendorTransactionId) { this.vendorTransactionId = vendorTransactionId; }
}
