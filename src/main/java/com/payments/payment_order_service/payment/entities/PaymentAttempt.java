package com.payments.payment_order_service.payment.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(nullable = false)
    private Long amount;

    @Column(nullable = false, length = 10)
    private String currency;

    @Column(name = "payment_method", nullable = false, length = 50)
    private String paymentMethod;

    @Column(length = 100)
    private String vendor;

    @Column(name = "vendor_transaction_id", length = 100)
    private String vendorTransactionId;

    @Column(name = "funding_source_identifier", length = 100)
    private String fundingSourceIdentifier;

    @Column(name = "funding_source_type", length = 50)
    private String fundingSourceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PaymentAttemptStatus status = PaymentAttemptStatus.PENDING;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public PaymentAttempt() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID orderId) { this.orderId = orderId; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public String getVendor() { return vendor; }
    public void setVendor(String vendor) { this.vendor = vendor; }
    public String getVendorTransactionId() { return vendorTransactionId; }
    public void setVendorTransactionId(String vendorTransactionId) { this.vendorTransactionId = vendorTransactionId; }
    public PaymentAttemptStatus getStatus() { return status; }
    public void setStatus(PaymentAttemptStatus status) { this.status = status; }
    public String getFundingSourceIdentifier() { return fundingSourceIdentifier; }
    public void setFundingSourceIdentifier(String fundingSourceIdentifier) { this.fundingSourceIdentifier = fundingSourceIdentifier; }
    public String getFundingSourceType() { return fundingSourceType; }
    public void setFundingSourceType(String fundingSourceType) { this.fundingSourceType = fundingSourceType; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
