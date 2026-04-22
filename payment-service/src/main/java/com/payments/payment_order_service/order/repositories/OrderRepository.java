package com.payments.payment_order_service.order.repositories;

import com.payments.payment_order_service.order.entities.PurchaseOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    @Query("SELECT o FROM PurchaseOrder o LEFT JOIN FETCH o.lineItems l WHERE o.id = :orderId ORDER BY l.id ASC")
    Optional<PurchaseOrder> findByIdWithLineItems(@Param("orderId") UUID orderId);

    @Query("SELECT o, p FROM PurchaseOrder o LEFT JOIN FETCH o.lineItems l LEFT JOIN com.payments.payment_order_service.payment.entities.PaymentAttempt p ON o.id = p.orderId WHERE o.id = :orderId ORDER BY l.id ASC")
    List<Object[]> findOrderWithPayments(@Param("orderId") UUID orderId);
}
