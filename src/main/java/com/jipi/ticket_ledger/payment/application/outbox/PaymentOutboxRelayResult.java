package com.jipi.ticket_ledger.payment.application.outbox;

public record PaymentOutboxRelayResult(
        int claimedCount,
        int publishedCount,
        int retryScheduledCount,
        int holdManualCount,
        int staleResultCount
) {
}
