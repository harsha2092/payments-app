package com.payments.payment_order_service.order.repositories;

import com.payments.payment_order_service.order.entities.PurchaseOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<PurchaseOrder, UUID> {
}
