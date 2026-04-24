package com.jipi.ticket_ledger.payment.infrastructure;

public record TossPaymentLookupResponse(
        String paymentKey,
        String orderId,
        String status,
        String method,
        Integer totalAmount,
        String currency
) {
}
