package com.payments.mock_vendor.dto;

import java.math.BigDecimal;

public class CreateOrderRequest {
    private BigDecimal amount;
    private String currency;
    private String receipt;
    private String paymentMethod;

    // Getters and Setters
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getReceipt() { return receipt; }
    public void setReceipt(String receipt) { this.receipt = receipt; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
}
