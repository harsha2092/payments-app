package com.payments.payment_order_service.order.dto.request;

import java.util.List;
import java.util.UUID;

public class CreateOrderRequest {
    private UUID userId;
    private String currency;
    private List<LineItemDto> items;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public List<LineItemDto> getItems() { return items; }
    public void setItems(List<LineItemDto> items) { this.items = items; }

    public static class LineItemDto {
        private String productId;
        private String productName;
        private Integer quantity;
        private Long unitPrice;

        public String getProductId() { return productId; }
        public void setProductId(String productId) { this.productId = productId; }
        public String getProductName() { return productName; }
        public void setProductName(String productName) { this.productName = productName; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
        public Long getUnitPrice() { return unitPrice; }
        public void setUnitPrice(Long unitPrice) { this.unitPrice = unitPrice; }
    }
}
