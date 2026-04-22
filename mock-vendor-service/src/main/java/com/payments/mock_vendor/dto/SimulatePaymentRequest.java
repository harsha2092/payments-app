package com.payments.mock_vendor.dto;

import java.util.Map;

public class SimulatePaymentRequest {
    private String targetStatus;
    private String paymentMethod;
    private boolean simulateWebhook;
    private Map<String, Object> metadata;

    // Getters and Setters
    public String getTargetStatus() { return targetStatus; }
    public void setTargetStatus(String targetStatus) { this.targetStatus = targetStatus; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public boolean isSimulateWebhook() { return simulateWebhook; }
    public void setSimulateWebhook(boolean simulateWebhook) { this.simulateWebhook = simulateWebhook; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
