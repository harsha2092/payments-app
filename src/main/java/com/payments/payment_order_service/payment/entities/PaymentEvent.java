package com.payments.payment_order_service.payment.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payment_events")
public class PaymentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "payment_attempt_id", nullable = false)
    private UUID paymentAttemptId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", nullable = false, length = 50)
    private PaymentAttemptStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 50)
    private PaymentAttemptStatus newStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected PaymentEvent() {}

    public PaymentEvent(UUID paymentAttemptId, PaymentAttemptStatus previousStatus, PaymentAttemptStatus newStatus) {
        this.paymentAttemptId = paymentAttemptId;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.createdAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }
}
