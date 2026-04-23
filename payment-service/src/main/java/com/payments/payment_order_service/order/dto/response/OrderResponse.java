package com.payments.payment_order_service.order.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.payments.payment_order_service.order.entities.OrderLineItem;
import com.payments.payment_order_service.order.entities.PurchaseOrder;
import com.payments.payment_order_service.payment.entities.PaymentAttempt;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class OrderResponse {

    private UUID id;
    private UUID userId;
    private Long totalAmount;
    private Long paidAmount;
    private String currency;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @JsonProperty("new")
    private boolean isNew;

    private List<LineItemDto> lineItems = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<PaymentDetailDto> paymentDetails;

    public OrderResponse() {}

    public static OrderResponse fromEntity(PurchaseOrder order, List<PaymentAttempt> payments) {
        OrderResponse response = new OrderResponse();
        response.setId(order.getId());
        response.setUserId(order.getUserId());
        response.setTotalAmount(order.getTotalAmount());
        response.setPaidAmount(order.getPaidAmount());
        response.setCurrency(order.getCurrency());
        response.setStatus(order.getStatus() != null ? order.getStatus().name() : null);
        response.setCreatedAt(order.getCreatedAt());
        response.setUpdatedAt(order.getUpdatedAt());
        response.setNew(order.isNew());

        if (order.getLineItems() != null) {
            java.util.Set<UUID> seenIds = new java.util.HashSet<>();
            response.setLineItems(order.getLineItems().stream()
                    .filter(item -> seenIds.add(item.getId()))
                    .map(LineItemDto::fromEntity)
                    .collect(Collectors.toList()));
        }

        if (payments != null) {
            response.setPaymentDetails(payments.stream()
                    .map(PaymentDetailDto::fromEntity)
                    .collect(Collectors.toList()));
        }

        return response;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public Long getTotalAmount() { return totalAmount; }
    public void setTotalAmount(Long totalAmount) { this.totalAmount = totalAmount; }
    public Long getPaidAmount() { return paidAmount; }
    public void setPaidAmount(Long paidAmount) { this.paidAmount = paidAmount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public boolean isNew() { return isNew; }
    public void setNew(boolean aNew) { isNew = aNew; }
    public List<LineItemDto> getLineItems() { return lineItems; }
    public void setLineItems(List<LineItemDto> lineItems) { this.lineItems = lineItems; }
    public List<PaymentDetailDto> getPaymentDetails() { return paymentDetails; }
    public void setPaymentDetails(List<PaymentDetailDto> paymentDetails) { this.paymentDetails = paymentDetails; }

    public static class LineItemDto {
        private UUID id;
        private String productId;
        private String productName;
        private Integer quantity;
        private Long unitPrice;
        private Long totalPrice;
        @JsonProperty("new")
        private boolean isNew;

        public static LineItemDto fromEntity(OrderLineItem item) {
            LineItemDto dto = new LineItemDto();
            dto.setId(item.getId());
            dto.setProductId(item.getProductId());
            dto.setProductName(item.getProductName());
            dto.setQuantity(item.getQuantity());
            dto.setUnitPrice(item.getUnitPrice());
            dto.setTotalPrice(item.getTotalPrice());
            dto.setNew(item.isNew());
            return dto;
        }

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public String getProductId() { return productId; }
        public void setProductId(String productId) { this.productId = productId; }
        public String getProductName() { return productName; }
        public void setProductName(String productName) { this.productName = productName; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
        public Long getUnitPrice() { return unitPrice; }
        public void setUnitPrice(Long unitPrice) { this.unitPrice = unitPrice; }
        public Long getTotalPrice() { return totalPrice; }
        public void setTotalPrice(Long totalPrice) { this.totalPrice = totalPrice; }
        public boolean isNew() { return isNew; }
        public void setNew(boolean aNew) { isNew = aNew; }
    }

    public static class PaymentDetailDto {
        private UUID id;
        private UUID orderId;
        private Long amount;
        private String currency;
        private String status;
        private String paymentMethod;
        private String vendor;
        private String gatewayOrderId;
        private String vendorTransactionId;
        private String fundingSourceType;
        private String fundingSourceIdentifier;
        private Long version;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        @JsonProperty("new")
        private boolean isNew;

        public static PaymentDetailDto fromEntity(PaymentAttempt attempt) {
            PaymentDetailDto dto = new PaymentDetailDto();
            dto.setId(attempt.getId());
            dto.setOrderId(attempt.getOrderId());
            dto.setAmount(attempt.getAmount());
            dto.setCurrency(attempt.getCurrency());
            dto.setStatus(attempt.getStatus() != null ? attempt.getStatus().name() : null);
            dto.setPaymentMethod(attempt.getPaymentMethod());
            dto.setVendor(attempt.getVendor());
            dto.setGatewayOrderId(attempt.getGatewayOrderId());
            dto.setVendorTransactionId(attempt.getVendorTransactionId());
            dto.setFundingSourceType(attempt.getFundingSourceType());
            dto.setFundingSourceIdentifier(attempt.getFundingSourceIdentifier());
            dto.setVersion(attempt.getVersion());
            dto.setCreatedAt(attempt.getCreatedAt());
            dto.setUpdatedAt(attempt.getUpdatedAt());
            dto.setNew(attempt.isNew());
            return dto;
        }

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public UUID getOrderId() { return orderId; }
        public void setOrderId(UUID orderId) { this.orderId = orderId; }
        public Long getAmount() { return amount; }
        public void setAmount(Long amount) { this.amount = amount; }
        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getPaymentMethod() { return paymentMethod; }
        public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
        public String getVendor() { return vendor; }
        public void setVendor(String vendor) { this.vendor = vendor; }
        public String getGatewayOrderId() { return gatewayOrderId; }
        public void setGatewayOrderId(String gatewayOrderId) { this.gatewayOrderId = gatewayOrderId; }
        public String getVendorTransactionId() { return vendorTransactionId; }
        public void setVendorTransactionId(String vendorTransactionId) { this.vendorTransactionId = vendorTransactionId; }
        public String getFundingSourceType() { return fundingSourceType; }
        public void setFundingSourceType(String fundingSourceType) { this.fundingSourceType = fundingSourceType; }
        public String getFundingSourceIdentifier() { return fundingSourceIdentifier; }
        public void setFundingSourceIdentifier(String fundingSourceIdentifier) { this.fundingSourceIdentifier = fundingSourceIdentifier; }
        public Long getVersion() { return version; }
        public void setVersion(Long version) { this.version = version; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
        public boolean isNew() { return isNew; }
        public void setNew(boolean aNew) { isNew = aNew; }
    }
}
