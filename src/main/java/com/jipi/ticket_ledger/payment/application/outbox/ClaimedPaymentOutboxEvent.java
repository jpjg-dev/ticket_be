package com.jipi.ticket_ledger.payment.application.outbox;

import java.util.UUID;

public record ClaimedPaymentOutboxEvent(
        Long outboxId,
        UUID eventId,
        Long paymentId,
        String payload,
        int retryCount,
        UUID claimToken
) {
}
