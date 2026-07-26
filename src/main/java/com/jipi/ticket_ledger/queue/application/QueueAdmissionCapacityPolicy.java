package com.jipi.ticket_ledger.queue.application;

import java.time.Duration;

public record QueueAdmissionCapacityPolicy(
        double configuredAdmissionRate,
        Duration maxBacklogDrainDuration
) {
}
