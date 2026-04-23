package com.payments.mock_vendor.controllers;

import com.github.f4b6a3.uuid.UuidCreator;
import com.payments.mock_vendor.dto.CreateOrderRequest;
import com.payments.mock_vendor.dto.SimulatePaymentRequest;
import com.payments.mock_vendor.entities.MockOrder;
import com.payments.mock_vendor.repositories.MockOrderRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/mock-vendor/orders")
public class MockOrderController {

    private final MockOrderRepository mockOrderRepository;

    public MockOrderController(MockOrderRepository mockOrderRepository) {
        this.mockOrderRepository = mockOrderRepository;
    }

    @PostMapping
    public ResponseEntity<MockOrder> createOrder(@RequestBody CreateOrderRequest request) {
        MockOrder order = new MockOrder();
        order.setId("mock_ord_" + UuidCreator.getTimeOrderedEpoch().toString().substring(0, 8).toUpperCase());
        order.setAmount(request.getAmount());
        order.setCurrency(request.getCurrency() != null ? request.getCurrency() : "USD");

        if ("WALLET".equalsIgnoreCase(request.getPaymentMethod())) {
            order.setStatus("SUCCESS");
            order.setPaymentMethod("WALLET");
            order.setVendorPaymentId(
                    "pay_wallet_" + UuidCreator.getTimeOrderedEpoch().toString().substring(0, 8).toUpperCase());
        } else {
            order.setStatus("CREATED");
        }

        MockOrder saved = mockOrderRepository.save(order);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{id}")
    public ResponseEntity<MockOrder> getOrder(@PathVariable String id) {
        return mockOrderRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/simulate")
    public ResponseEntity<MockOrder> simulatePayment(
            @PathVariable String id,
            @RequestBody SimulatePaymentRequest request) {

        return mockOrderRepository.findById(id).map(order -> {
            order.setStatus(request.getTargetStatus() != null ? request.getTargetStatus() : "CAPTURED");
            order.setPaymentMethod(request.getPaymentMethod());
            order.setVendorPaymentId(
                    "pay_" + UuidCreator.getTimeOrderedEpoch().toString().substring(0, 8).toUpperCase());
            order.setUpdatedAt(LocalDateTime.now());

            if (request.getMetadata() != null) {
                order.setMetadata(request.getMetadata());
            }

            MockOrder saved = mockOrderRepository.save(order);

            // TODO: In Phase 4, we would add the async webhook dispatching logic here
            // if (request.isSimulateWebhook()) {
            // webhookService.dispatchAsync(saved);
            // }

            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }
}
