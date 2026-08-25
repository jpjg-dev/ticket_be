package com.jipi.ticket_ledger.payment.application.outbox;

import java.util.UUID;

public record PaymentOutboxRequeueResult(
        UUID eventId,
        Long paymentId,
        String status
) {
}
