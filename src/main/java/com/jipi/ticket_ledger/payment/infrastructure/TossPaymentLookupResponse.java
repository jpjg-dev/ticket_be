package com.jipi.ticket_ledger.payment.infrastructure;

import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayPayment;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayState;

public record TossPaymentLookupResponse(
        String paymentKey,
        String orderId,
        String status,
        String method,
        Integer totalAmount,
        Integer balanceAmount,
        String currency
) implements PaymentGatewayPayment {

    public TossPaymentLookupResponse(
            String paymentKey,
            String orderId,
            String status,
            String method,
            Integer totalAmount,
            String currency
    ) {
        this(paymentKey, orderId, status, method, totalAmount, inferredBalanceAmount(status, totalAmount), currency);
    }

    @Override
    public PaymentGatewayState state() {
        return TossPaymentStatus.toGatewayState(status);
    }

    private static Integer inferredBalanceAmount(String status, Integer totalAmount) {
        return switch (status) {
            case "CANCELED" -> 0;
            case "DONE" -> totalAmount;
            default -> null;
        };
    }
}
