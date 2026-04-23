package com.payments.payment_order_service.payment.clients;

import com.payments.payment_order_service.payment.business_models.GatewayOrderRequest;
import com.payments.payment_order_service.payment.business_models.GatewayOrderResponse;
import com.payments.payment_order_service.payment.business_models.VerifiedPaymentResult;
import com.payments.payment_order_service.payment.entities.PaymentAttempt;
import com.payments.payment_order_service.payment.entities.PaymentAttemptStatus;
import org.springframework.stereotype.Component;

@Component
public class InternalWalletClient implements PaymentGatewayClient {

    @Override
    public GatewayOrderResponse createOrder(GatewayOrderRequest request) {
        // Wallet does not require an external physical order, return a dummy internal one
        GatewayOrderResponse response = new GatewayOrderResponse();
        response.setId("wallet_ord_dummy");
        response.setAmount(request.getAmount());
        response.setCurrency(request.getCurrency());
        response.setStatus("CREATED");
        return response;
    }

    @Override
    public boolean supports(String vendor) {
        return "INTERNAL".equalsIgnoreCase(vendor);
    }

    @Override
    public VerifiedPaymentResult verifyPaymentStatus(PaymentAttempt attempt) {
        // MOCK: Represents synchronous check against ledger integrity
        String mockJson = "{ \"ledger_status\": \"LOCKED\", \"wallet_balance_deducted\": true }";

        return new VerifiedPaymentResult(
                PaymentAttemptStatus.SUCCESS,
                "txn_wallet_123",
                "WALLET",
                null,
                mockJson
        );
    }
}
