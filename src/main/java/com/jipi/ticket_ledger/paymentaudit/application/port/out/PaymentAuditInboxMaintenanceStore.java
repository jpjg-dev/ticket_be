package com.jipi.ticket_ledger.paymentaudit.application.port.out;

import java.time.Instant;

public interface PaymentAuditInboxMaintenanceStore {

    int deleteProcessedBefore(Instant cutoff, int batchSize);

    InboxRetentionSnapshot getRetentionSnapshot(Instant now);

    record InboxRetentionSnapshot(long recordCount, long oldestAgeSeconds) {
    }
}
