package com.jipi.ticket_ledger.payment.application.outbox;

public record PaymentOutboxBacklogSnapshot(
        long pendingCount,
        long holdManualCount,
        long activeLeaseCount,
        long oldestPendingAgeSeconds
) {
}
