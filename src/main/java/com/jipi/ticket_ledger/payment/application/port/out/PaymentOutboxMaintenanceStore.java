package com.jipi.ticket_ledger.payment.application.port.out;

import java.time.Instant;

public interface PaymentOutboxMaintenanceStore {

    int deletePublishedBefore(Instant cutoff, int batchSize);
}
