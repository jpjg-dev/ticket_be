package com.jipi.ticket_ledger.payment.application.model;

public record ReadyPaymentResult(
        Long paymentId,
        String orderId,
        Integer totalAmount,
        Integer seatTotalAmount,
        Integer vatAmount,
        String orderName,
        String currency
) {
}
