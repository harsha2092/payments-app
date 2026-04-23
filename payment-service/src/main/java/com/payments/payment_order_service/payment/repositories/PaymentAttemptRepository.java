package com.payments.payment_order_service.payment.repositories;

import com.payments.payment_order_service.payment.entities.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {
    List<PaymentAttempt> findByOrderId(UUID orderId);

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(p) FROM PaymentAttempt p WHERE p.orderId = :orderId AND p.status != 'FAILED' AND p.paymentMethod != 'WALLET'")
    long countActiveExternalAttempts(@org.springframework.data.repository.query.Param("orderId") UUID orderId);
}
