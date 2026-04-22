package com.payments.payment_order_service.payment.business_models;

import java.util.List;

public class PaymentMethodMetadata {
    private String method;
    private boolean isSync;
    private int executionPriority;
    private List<String> combinableWith;
    private Long balance;

    public PaymentMethodMetadata() {}

    public PaymentMethodMetadata(String method, boolean isSync, int executionPriority, List<String> combinableWith, Long balance) {
        this.method = method;
        this.isSync = isSync;
        this.executionPriority = executionPriority;
        this.combinableWith = combinableWith;
        this.balance = balance;
    }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public boolean isSync() { return isSync; }
    public void setSync(boolean sync) { isSync = sync; }
    public int getExecutionPriority() { return executionPriority; }
    public void setExecutionPriority(int executionPriority) { this.executionPriority = executionPriority; }
    public List<String> getCombinableWith() { return combinableWith; }
    public void setCombinableWith(List<String> combinableWith) { this.combinableWith = combinableWith; }
    public Long getBalance() { return balance; }
    public void setBalance(Long balance) { this.balance = balance; }
}
