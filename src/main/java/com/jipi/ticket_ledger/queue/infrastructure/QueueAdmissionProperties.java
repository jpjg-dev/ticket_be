package com.jipi.ticket_ledger.queue.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("queue.admission")
public record QueueAdmissionProperties(
        int batchSize,
        long fixedDelayMs,
        Duration entryTtl,
        Duration admissionTtl,
        Duration claimTtl,
        Duration streamTimeout,
        Duration streamInterval
) {
}
