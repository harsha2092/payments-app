package com.payments.payment_order_service.order.controllers;

import com.payments.payment_order_service.order.dto.request.CreateOrderRequest;
import com.payments.payment_order_service.order.entities.PurchaseOrder;
import com.payments.payment_order_service.order.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<PurchaseOrder> createOrder(@RequestBody CreateOrderRequest request) {
        PurchaseOrder order = orderService.createOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PurchaseOrder> getOrder(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.getOrder(id));
    }
}
