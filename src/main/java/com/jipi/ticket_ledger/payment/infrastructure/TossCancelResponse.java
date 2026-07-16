package com.jipi.ticket_ledger.payment.infrastructure;

import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayPayment;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayState;

public record TossCancelResponse(String paymentKey,
                                 String status,
                                 Integer totalAmount,
                                 Integer balanceAmount,
                                 String currency) implements PaymentGatewayPayment {

    public TossCancelResponse(String paymentKey, String status, Integer totalAmount, String currency) {
        this(paymentKey, status, totalAmount, inferredBalanceAmount(status, totalAmount), currency);
    }

    @Override
    public String orderId() {
        return null;
    }

    @Override
    public String method() {
        return null;
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
