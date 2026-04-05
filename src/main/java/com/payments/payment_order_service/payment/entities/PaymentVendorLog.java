package com.payments.payment_order_service.payment.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payment_vendor_logs")
public class PaymentVendorLog {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "payment_attempt_id", nullable = false)
    private UUID paymentAttemptId;

    @Column(name = "event_type", length = 50)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "vendor_metadata", columnDefinition = "jsonb")
    private String vendorMetadata;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public PaymentVendorLog() {}

    public PaymentVendorLog(UUID id, UUID paymentAttemptId, String eventType, String vendorMetadata, LocalDateTime createdAt) {
        this.id = id;
        this.paymentAttemptId = paymentAttemptId;
        this.eventType = eventType;
        this.vendorMetadata = vendorMetadata;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getPaymentAttemptId() { return paymentAttemptId; }
    public void setPaymentAttemptId(UUID paymentAttemptId) { this.paymentAttemptId = paymentAttemptId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getVendorMetadata() { return vendorMetadata; }
    public void setVendorMetadata(String vendorMetadata) { this.vendorMetadata = vendorMetadata; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
