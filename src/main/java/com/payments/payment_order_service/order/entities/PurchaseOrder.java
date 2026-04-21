package com.payments.payment_order_service.order.entities;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.payments.payment_order_service.common.entities.AbstractEntity;

@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder extends AbstractEntity<UUID> {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "total_amount", nullable = false)
    private Long totalAmount;

    @Column(name = "paid_amount", nullable = false)
    private Long paidAmount = 0L;

    @Column(nullable = false, length = 10)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private OrderStatus status = OrderStatus.CREATED;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderLineItem> lineItems = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public PurchaseOrder() {}

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
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public List<OrderLineItem> getLineItems() { return lineItems; }
    public void setLineItems(List<OrderLineItem> lineItems) { this.lineItems = lineItems; }
    public void addLineItem(OrderLineItem item) {
        lineItems.add(item);
        item.setOrder(this);
    }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
