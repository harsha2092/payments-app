package com.payments.payment_order_service.payment.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.payments.payment_order_service.order.entities.PurchaseOrder;
import com.payments.payment_order_service.order.service.OrderService;
import com.payments.payment_order_service.payment.business_models.PaymentMethodMetadata;
import com.payments.payment_order_service.payment.business_models.VerifiedPaymentResult;
import com.payments.payment_order_service.payment.clients.GatewayClientFactory;
import com.payments.payment_order_service.payment.clients.PaymentGatewayClient;
import com.payments.payment_order_service.payment.dto.request.CreatePaymentRequest;
import com.payments.payment_order_service.payment.dto.request.UpdatePaymentStatusRequest;
import com.payments.payment_order_service.payment.dto.response.EligiblePaymentMethodsResponse;
import com.payments.payment_order_service.payment.entities.PaymentAttempt;
import com.payments.payment_order_service.payment.entities.PaymentAttemptStatus;
import com.payments.payment_order_service.payment.entities.PaymentEvent;
import com.payments.payment_order_service.payment.entities.PaymentVendorLog;
import com.payments.payment_order_service.payment.repositories.PaymentAttemptRepository;
import com.payments.payment_order_service.payment.repositories.PaymentEventRepository;
import com.payments.payment_order_service.payment.repositories.PaymentVendorLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class PaymentService {

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final OrderService orderService;
    private final PaymentEventRepository paymentEventRepository;
    private final GatewayClientFactory gatewayClientFactory;
    private final PaymentVendorLogRepository paymentVendorLogRepository;

    public PaymentService(PaymentAttemptRepository paymentAttemptRepository,
                          OrderService orderService,
                          PaymentEventRepository paymentEventRepository,
                          GatewayClientFactory gatewayClientFactory,
                          PaymentVendorLogRepository paymentVendorLogRepository) {
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.orderService = orderService;
        this.paymentEventRepository = paymentEventRepository;
        this.gatewayClientFactory = gatewayClientFactory;
        this.paymentVendorLogRepository = paymentVendorLogRepository;
    }

    @Transactional
    public PaymentAttempt createPaymentAttempt(UUID orderId, CreatePaymentRequest request) {
        // Ensure order exists before allowing payment (lightweight check across module)
        PurchaseOrder order = orderService.getOrder(orderId);

        long remainingAmount = order.getTotalAmount() - order.getPaidAmount();
        if (request.getAmount() > remainingAmount) {
            throw new IllegalArgumentException("Payment amount exceeds order remaining amount");
        }

        List<PaymentAttempt> existingAttempts = paymentAttemptRepository.findByOrderId(orderId);

        long activeExternalAttemptsCount = existingAttempts.stream()
                .filter(a -> a.getStatus() != PaymentAttemptStatus.FAILED)
                .filter(a -> !"WALLET".equalsIgnoreCase(a.getPaymentMethod()))
                .count();

        if (!"WALLET".equalsIgnoreCase(request.getPaymentMethod()) && activeExternalAttemptsCount >= 1) {
            throw new IllegalArgumentException("Only one active external payment method is allowed per order");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(UuidCreator.getTimeOrderedEpoch());
        attempt.setOrderId(orderId);
        attempt.setAmount(request.getAmount());
        attempt.setCurrency(request.getCurrency());
        attempt.setPaymentMethod(request.getPaymentMethod());
        attempt.setVendor(request.getVendor());

        if ("WALLET".equalsIgnoreCase(request.getPaymentMethod())) {
            attempt.setStatus(PaymentAttemptStatus.SUCCESS);
        } else {
            attempt.setStatus(PaymentAttemptStatus.PENDING);
        }

        attempt.setCreatedAt(now);
        attempt.setUpdatedAt(now);

        PaymentAttempt savedAttempt = paymentAttemptRepository.save(attempt);

        if ("WALLET".equalsIgnoreCase(request.getPaymentMethod())) {
            paymentEventRepository.save(new PaymentEvent(savedAttempt.getId(), PaymentAttemptStatus.PENDING, PaymentAttemptStatus.SUCCESS));
            orderService.recordPaymentSuccess(orderId, savedAttempt.getAmount());
        }

        return savedAttempt;
    }

    @Transactional
    public PaymentAttempt updatePaymentStatus(UUID paymentId, UpdatePaymentStatusRequest request) {
        PaymentAttempt attempt = paymentAttemptRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment Attempt not found: " + paymentId));

        // ZT Architecture: We no longer blindly trust request.getStatus().
        // Instead, we use the abstract Factory pattern to query the authentic vendor payload securely.
        PaymentGatewayClient client = gatewayClientFactory.getClient(attempt.getVendor());
        VerifiedPaymentResult vendorResult = client.verifyPaymentStatus(attempt);

        PaymentAttemptStatus targetStatus = vendorResult.getStatus(); // Used authentic backend status
        PaymentAttemptStatus currentStatus = attempt.getStatus();

        if (currentStatus == targetStatus) {
            return attempt; // Idempotent
        }

        PaymentAttemptStatus.validateTransition(currentStatus, targetStatus);

        attempt.setStatus(targetStatus);
        attempt.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        // Push abstracted Hot columns natively into the database
        attempt.setVendorTransactionId(vendorResult.getVendorTransactionId());
        attempt.setFundingSourceIdentifier(vendorResult.getFundingSourceIdentifier());
        attempt.setFundingSourceType(vendorResult.getFundingSourceType());

        PaymentAttempt savedAttempt = paymentAttemptRepository.saveAndFlush(attempt);

        paymentEventRepository.save(new PaymentEvent(savedAttempt.getId(), currentStatus, targetStatus));

        // Separate Cold Column JSONB Logging Table
        PaymentVendorLog log = new PaymentVendorLog(
                UuidCreator.getTimeOrderedEpoch(),
                savedAttempt.getId(),
                targetStatus.name(),
                vendorResult.getRawJsonPayload(),
                LocalDateTime.now(ZoneOffset.UTC)
        );
        paymentVendorLogRepository.save(log);

        // If the payment succeeded, notify the Order module to update the cart's paid amount
        if (targetStatus == PaymentAttemptStatus.SUCCESS) {
            orderService.recordPaymentSuccess(savedAttempt.getOrderId(), savedAttempt.getAmount());
        }

        return savedAttempt;
    }

    public List<PaymentAttempt> getPaymentsForOrder(UUID orderId) {
        return paymentAttemptRepository.findByOrderId(orderId);
    }

    public EligiblePaymentMethodsResponse getEligibleMethods(UUID orderId) {
        // Validate order exists
        orderService.getOrder(orderId);

        // Mock wallet balance for the user
        long mockWalletBalance = 5000L;

        // Determine supported methods and their topological metadata
        List<PaymentMethodMetadata> supportedMethods = List.of(
                new PaymentMethodMetadata("WALLET", true, 1, List.of("UPI", "CARD", "NETBANKING"), mockWalletBalance),
                new PaymentMethodMetadata("UPI", false, 2, List.of("WALLET"), null),
                new PaymentMethodMetadata("CARD", false, 2, List.of("WALLET"), null),
                new PaymentMethodMetadata("NETBANKING", false, 2, List.of("WALLET"), null)
        );

        return new EligiblePaymentMethodsResponse(supportedMethods);
    }
}
