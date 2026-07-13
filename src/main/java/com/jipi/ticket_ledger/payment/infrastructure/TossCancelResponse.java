package com.jipi.ticket_ledger.payment.infrastructure;

import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayPayment;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayState;

public record TossCancelResponse(String paymentKey,
                                 String status,
                                 Integer totalAmount,
                                 String currency) implements PaymentGatewayPayment {

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
}
