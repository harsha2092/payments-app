package com.payments.payment_order_service.payment.repositories;

import com.payments.payment_order_service.payment.entities.PaymentVendorLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PaymentVendorLogRepository extends JpaRepository<PaymentVendorLog, UUID> {
}
