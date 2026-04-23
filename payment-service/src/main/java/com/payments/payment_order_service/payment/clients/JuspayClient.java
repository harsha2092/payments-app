package com.payments.payment_order_service.payment.clients;

import com.payments.payment_order_service.payment.business_models.GatewayOrderRequest;
import com.payments.payment_order_service.payment.business_models.GatewayOrderResponse;
import com.payments.payment_order_service.payment.business_models.VerifiedPaymentResult;
import com.payments.payment_order_service.payment.entities.PaymentAttempt;
import com.payments.payment_order_service.payment.entities.PaymentAttemptStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class JuspayClient implements PaymentGatewayClient {

    private final RestTemplate restTemplate;
    private final String mockVendorUrl;
    private final com.payments.payment_order_service.payment.repositories.PaymentVendorLogRepository logRepo;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public JuspayClient(RestTemplate restTemplate,
            @Value("${mock-vendor.url}") String mockVendorUrl,
            com.payments.payment_order_service.payment.repositories.PaymentVendorLogRepository logRepo,
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.restTemplate = restTemplate;
        this.mockVendorUrl = mockVendorUrl;
        this.logRepo = logRepo;
        this.mapper = mapper;
    }

    @Override
    public GatewayOrderResponse createOrder(GatewayOrderRequest request) {
        String url = mockVendorUrl + "/mock-vendor/orders";

        // Fetch raw JSON payload structurally returning from the Gateway
        org.springframework.http.ResponseEntity<String> responseEntity = restTemplate.postForEntity(url, request,
                String.class);
        String rawJson = responseEntity.getBody();

        // 100% Truth: Append real external intent record without mutation wrapper.
        logRepo.save(new com.payments.payment_order_service.payment.entities.PaymentVendorLog(
                com.github.f4b6a3.uuid.UuidCreator.getTimeOrderedEpoch(),
                request.getPaymentAttemptId(),
                "ORDER_CREATED",
                rawJson,
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)));

        try {
            return mapper.readValue(rawJson, GatewayOrderResponse.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to decode gateway order response", e);
        }
    }

    @Override
    public boolean supports(String vendor) {
        return "JUSPAY".equalsIgnoreCase(vendor);
    }

    @Override
    public VerifiedPaymentResult verifyPaymentStatus(PaymentAttempt attempt) {
        String url = mockVendorUrl + "/mock-vendor/orders/" + attempt.getGatewayOrderId();

        try {
            org.springframework.http.ResponseEntity<String> responseEntity = restTemplate.getForEntity(url,
                    String.class);
            String rawJson = responseEntity.getBody();

            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(rawJson);
            String mockStatus = root.path("status").asText();
            String vendorTransactionId = root.path("vendorPaymentId").isNull() ? null
                    : root.path("vendorPaymentId").asText();
            String paymentMethod = root.path("paymentMethod").isNull() ? null : root.path("paymentMethod").asText();

            PaymentAttemptStatus targetStatus = PaymentAttemptStatus.mapFromGatewayStatus(mockStatus);

            return new VerifiedPaymentResult(
                    targetStatus,
                    vendorTransactionId,
                    paymentMethod,
                    null, // fundingSourceType
                    rawJson);
        } catch (Exception e) {
            throw new RuntimeException("Failed to verify payment status with Juspay", e);
        }
    }
}
