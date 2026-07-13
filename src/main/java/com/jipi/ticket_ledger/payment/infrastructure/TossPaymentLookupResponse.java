package com.jipi.ticket_ledger.payment.infrastructure;

import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayPayment;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayState;

public record TossPaymentLookupResponse(
        String paymentKey,
        String orderId,
        String status,
        String method,
        Integer totalAmount,
        String currency
) implements PaymentGatewayPayment {

    @Override
    public PaymentGatewayState state() {
        return TossPaymentStatus.toGatewayState(status);
    }
}
