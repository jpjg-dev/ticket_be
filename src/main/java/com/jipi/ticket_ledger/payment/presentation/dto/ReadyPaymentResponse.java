package com.jipi.ticket_ledger.payment.presentation.dto;

public record ReadyPaymentResponse(
        Long paymentId,
        String orderId,
        Integer amount,
        Integer seatTotalAmount,
        Integer vatAmount,
        String orderName,
        String currency
) {
}
