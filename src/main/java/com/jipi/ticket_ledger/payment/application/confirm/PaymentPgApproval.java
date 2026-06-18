package com.jipi.ticket_ledger.payment.application.confirm;

import com.jipi.ticket_ledger.payment.infrastructure.TossConfirmResponse;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentLookupResponse;

record PaymentPgApproval(
        String paymentKey,
        String orderId,
        String status,
        String method,
        Integer totalAmount,
        String currency
) {
    static PaymentPgApproval from(TossConfirmResponse response) {
        return new PaymentPgApproval(
                response.paymentKey(),
                response.orderId(),
                response.status(),
                response.method(),
                response.totalAmount(),
                response.currency()
        );
    }

    static PaymentPgApproval from(TossPaymentLookupResponse response) {
        return new PaymentPgApproval(
                response.paymentKey(),
                response.orderId(),
                response.status(),
                response.method(),
                response.totalAmount(),
                response.currency()
        );
    }
}
