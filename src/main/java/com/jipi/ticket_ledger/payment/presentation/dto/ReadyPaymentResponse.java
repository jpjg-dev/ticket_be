package com.jipi.ticket_ledger.payment.presentation.dto;

public record ReadyPaymentResponse(
        Long paymentId,
        String orderId,
        Integer amount,
        String orderName
) {
}
