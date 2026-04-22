package com.payments.payment_order_service.payment.controllers;

import com.payments.payment_order_service.payment.dto.request.CreatePaymentRequest;
import com.payments.payment_order_service.payment.dto.request.UpdatePaymentStatusRequest;
import com.payments.payment_order_service.payment.dto.response.EligiblePaymentMethodsResponse;
import com.payments.payment_order_service.payment.entities.PaymentAttempt;
import com.payments.payment_order_service.payment.service.PaymentService;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class PaymentController {

    private final PaymentService paymentService;
    private static final int MAX_RETRIES = 3;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/orders/{orderId}/payments")
    public ResponseEntity<PaymentAttempt> createPayment(
            @PathVariable UUID orderId,
            @RequestBody CreatePaymentRequest request) {
        PaymentAttempt attempt = paymentService.createPaymentAttempt(orderId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(attempt);
    }

    @PutMapping("/payments/{paymentId}/status")
    public ResponseEntity<PaymentAttempt> updateStatus(
            @PathVariable UUID paymentId,
            @RequestBody UpdatePaymentStatusRequest request) {

        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                PaymentAttempt attempt = paymentService.updatePaymentStatus(paymentId, request);
                return ResponseEntity.ok(attempt);
            } catch (OptimisticLockingFailureException e) {
                if (i == MAX_RETRIES - 1) throw e;
            }
        }
        throw new OptimisticLockingFailureException("Retries exhausted for payment " + paymentId);
    }

    @GetMapping("/orders/{orderId}/eligible-methods")
    public ResponseEntity<EligiblePaymentMethodsResponse> getEligibleMethods(@PathVariable UUID orderId) {
        EligiblePaymentMethodsResponse response = paymentService.getEligibleMethods(orderId);
        return ResponseEntity.ok(response);
    }
}
