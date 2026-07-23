package com.jipi.ticket_ledger.payment.application.confirm;

import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayPayment;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayState;

record PaymentPgApproval(
        String paymentKey,
        String orderId,
        String status,
        String method,
        Integer totalAmount,
        String currency,
        PaymentGatewayState state
) {
    static PaymentPgApproval from(PaymentGatewayPayment response) {
        return new PaymentPgApproval(
                response.paymentKey(),
                response.orderId(),
                response.status(),
                response.method(),
                response.totalAmount(),
                response.currency(),
                response.state()
        );
    }
}
