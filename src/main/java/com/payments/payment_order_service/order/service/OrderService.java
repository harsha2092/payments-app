package com.payments.payment_order_service.order.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.payments.payment_order_service.order.dto.request.CreateOrderRequest;
import com.payments.payment_order_service.order.entities.OrderLineItem;
import com.payments.payment_order_service.order.entities.OrderStatus;
import com.payments.payment_order_service.order.entities.PurchaseOrder;
import com.payments.payment_order_service.order.repositories.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional
    public PurchaseOrder createOrder(CreateOrderRequest request) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        PurchaseOrder order = new PurchaseOrder();
        order.setId(UuidCreator.getTimeOrderedEpoch());
        order.setUserId(request.getUserId());
        order.setCurrency(request.getCurrency());
        order.setCreatedAt(now);
        order.setUpdatedAt(now);

        long totalAmount = 0L;
        if (request.getItems() != null) {
            for (CreateOrderRequest.LineItemDto itemDto : request.getItems()) {
                OrderLineItem item = new OrderLineItem();
                item.setId(UuidCreator.getTimeOrderedEpoch());
                item.setProductId(itemDto.getProductId());
                item.setProductName(itemDto.getProductName());
                item.setQuantity(itemDto.getQuantity());
                item.setUnitPrice(itemDto.getUnitPrice());

                long itemTotal = itemDto.getQuantity() * itemDto.getUnitPrice();
                item.setTotalPrice(itemTotal);
                totalAmount += itemTotal;

                order.addLineItem(item);
            }
        }
        order.setTotalAmount(totalAmount);

        return orderRepository.save(order);
    }

    public PurchaseOrder getOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
    }

    @Transactional
    public void recordPaymentSuccess(UUID orderId, Long amount) {
        PurchaseOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        long newPaidAmount = order.getPaidAmount() + amount;
        order.setPaidAmount(newPaidAmount);

        if (newPaidAmount >= order.getTotalAmount()) {
            order.setStatus(OrderStatus.PAID);
        } else {
            order.setStatus(OrderStatus.PARTIALLY_PAID);
        }
        order.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        orderRepository.save(order);
    }
}
