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
    public ResponseEntity<com.payments.payment_order_service.order.dto.response.OrderResponse> getOrder(
            @PathVariable UUID id,
            @RequestParam(required = false, defaultValue = "false") boolean includePayments) {
        if (includePayments) {
            return ResponseEntity.ok(orderService.getOrderWithPayments(id));
        } else {
            PurchaseOrder order = orderService.getOrder(id);
            return ResponseEntity.ok(new com.payments.payment_order_service.order.dto.response.OrderResponse(order, null));
        }
    }
}
