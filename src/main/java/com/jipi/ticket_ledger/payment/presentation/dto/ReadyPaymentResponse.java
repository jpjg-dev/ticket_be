package com.jipi.ticket_ledger.payment.presentation.dto;

import com.jipi.ticket_ledger.payment.application.model.ReadyPaymentResult;

public record ReadyPaymentResponse(
        Long paymentId,
        String orderId,
        Integer amount,
        Integer seatTotalAmount,
        Integer vatAmount,
        String orderName,
        String currency
) {
    public static ReadyPaymentResponse from(ReadyPaymentResult result) {
        return new ReadyPaymentResponse(
                result.paymentId(), result.orderId(), result.totalAmount(), result.seatTotalAmount(),
                result.vatAmount(), result.orderName(), result.currency()
        );
    }
}
