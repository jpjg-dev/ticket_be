package com.jipi.ticket_ledger.paymentaudit.application.model;

import java.time.Instant;

public record PaymentAuditRecordMetadata(
        String topic,
        int partition,
        long offset,
        Instant receivedAt
) {
}
