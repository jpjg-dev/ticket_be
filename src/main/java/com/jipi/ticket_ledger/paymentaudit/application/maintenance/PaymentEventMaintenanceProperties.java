package com.jipi.ticket_ledger.paymentaudit.application.maintenance;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "payment.event-maintenance")
public record PaymentEventMaintenanceProperties(
        boolean enabled,
        Duration fixedDelay,
        int batchSize,
        Duration outboxPublishedRetention,
        Duration inboxRetention,
        Duration dltRetention
) {

    public PaymentEventMaintenanceProperties {
        requirePositive(fixedDelay, "fixed-delay");
        requirePositive(outboxPublishedRetention, "outbox-published-retention");
        requirePositive(inboxRetention, "inbox-retention");
        requirePositive(dltRetention, "dlt-retention");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("payment.event-maintenance.batch-size는 1 이상이어야 합니다.");
        }
        if (inboxRetention.compareTo(dltRetention) <= 0) {
            throw new IllegalArgumentException("Inbox retention은 DLT retention보다 길어야 합니다.");
        }
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("payment.event-maintenance." + property + "는 양수여야 합니다.");
        }
    }
}
