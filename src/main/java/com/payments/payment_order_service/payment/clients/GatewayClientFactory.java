package com.payments.payment_order_service.payment.clients;

import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class GatewayClientFactory {

    private final List<PaymentGatewayClient> clients;

    public GatewayClientFactory(List<PaymentGatewayClient> clients) {
        this.clients = clients;
    }

    public PaymentGatewayClient getClient(String vendor) {
        if (vendor == null) {
            throw new IllegalArgumentException("Vendor cannot be null");
        }
        return clients.stream()
                .filter(client -> client.supports(vendor))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No configured gateway client found for vendor: " + vendor));
    }
}
