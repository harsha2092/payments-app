package com.payments.payment_order_service.order.repositories;

import com.payments.payment_order_service.order.entities.PurchaseOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    @Query("SELECT o, p FROM PurchaseOrder o LEFT JOIN com.payments.payment_order_service.payment.entities.PaymentAttempt p ON o.id = p.orderId WHERE o.id = :orderId")
    List<Object[]> findOrderWithPayments(@Param("orderId") UUID orderId);
}
