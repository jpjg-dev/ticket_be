package com.jipi.ticket_ledger.payment.presentation.dto;

import com.jipi.ticket_ledger.payment.application.outbox.PaymentOutboxRequeueResult;

import java.util.UUID;

public record PaymentOutboxRequeueResponse(
        UUID eventId,
        Long paymentId,
        String status
) {
    public static PaymentOutboxRequeueResponse from(PaymentOutboxRequeueResult result) {
        return new PaymentOutboxRequeueResponse(result.eventId(), result.paymentId(), result.status());
    }
}
