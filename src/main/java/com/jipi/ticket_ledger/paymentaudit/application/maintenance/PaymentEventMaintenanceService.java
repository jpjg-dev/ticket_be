package com.jipi.ticket_ledger.paymentaudit.application.maintenance;

import com.jipi.ticket_ledger.payment.application.port.out.PaymentOutboxMaintenanceStore;
import com.jipi.ticket_ledger.paymentaudit.application.observability.PaymentEventRetentionMetrics;
import com.jipi.ticket_ledger.paymentaudit.application.port.out.PaymentAuditInboxMaintenanceStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentEventMaintenanceService {

    private final PaymentOutboxMaintenanceStore paymentOutboxMaintenanceStore;
    private final PaymentAuditInboxMaintenanceStore paymentAuditInboxMaintenanceStore;
    private final PaymentEventMaintenanceProperties properties;
    private final PaymentEventRetentionMetrics metrics;
    private final Clock clock;

    public PaymentEventMaintenanceResult cleanup() {
        Instant now = clock.instant();
        CleanupAttempt outbox = cleanupOutbox(now);
        CleanupAttempt inbox = cleanupInbox(now);
        boolean metricsUpdated = refreshInboxMetrics(now);
        if (outbox.success() && inbox.success() && metricsUpdated) {
            metrics.recordSuccess(now);
        }
        return new PaymentEventMaintenanceResult(outbox.deletedCount(), inbox.deletedCount());
    }

    private CleanupAttempt cleanupOutbox(Instant now) {
        try {
            int deleted = paymentOutboxMaintenanceStore.deletePublishedBefore(
                    now.minus(properties.outboxPublishedRetention()),
                    properties.batchSize()
            );
            metrics.recordDeleted("outbox", deleted);
            return CleanupAttempt.success(deleted);
        } catch (RuntimeException exception) {
            log.error("Payment Outbox retention cleanup failed.", exception);
            return CleanupAttempt.failed();
        }
    }

    private CleanupAttempt cleanupInbox(Instant now) {
        try {
            int deleted = paymentAuditInboxMaintenanceStore.deleteProcessedBefore(
                    now.minus(properties.inboxRetention()),
                    properties.batchSize()
            );
            metrics.recordDeleted("inbox", deleted);
            return CleanupAttempt.success(deleted);
        } catch (RuntimeException exception) {
            log.error("Payment Inbox retention cleanup failed.", exception);
            return CleanupAttempt.failed();
        }
    }

    private boolean refreshInboxMetrics(Instant now) {
        try {
            PaymentAuditInboxMaintenanceStore.InboxRetentionSnapshot snapshot =
                    paymentAuditInboxMaintenanceStore.getRetentionSnapshot(now);
            metrics.updateInbox(snapshot.recordCount(), snapshot.oldestAgeSeconds());
            return true;
        } catch (RuntimeException exception) {
            log.error("Payment Inbox retention metrics refresh failed.", exception);
            return false;
        }
    }

    private record CleanupAttempt(boolean success, int deletedCount) {
        private static CleanupAttempt success(int deletedCount) {
            return new CleanupAttempt(true, deletedCount);
        }

        private static CleanupAttempt failed() {
            return new CleanupAttempt(false, 0);
        }
    }
}
