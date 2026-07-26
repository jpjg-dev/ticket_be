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
        Duration streamInterval,
        int maxExpectedBacklog,
        double minimumAdmissionRatePerSecond,
        Duration safetyMargin
) {

    public QueueAdmissionProperties {
        if (batchSize <= 0 || fixedDelayMs <= 0 || maxExpectedBacklog <= 0) {
            throw new IllegalArgumentException("Queue admission capacity values must be positive");
        }
        if (!Double.isFinite(minimumAdmissionRatePerSecond)
                || minimumAdmissionRatePerSecond <= 0
                || minimumAdmissionRatePerSecond > admissionsPerSecond(batchSize, fixedDelayMs)) {
            throw new IllegalArgumentException(
                    "Minimum queue admission rate must be positive and not exceed the configured rate"
            );
        }
        requirePositive(entryTtl, "Queue entry TTL");
        requirePositive(admissionTtl, "Queue admission TTL");
        requirePositive(claimTtl, "Queue claim TTL");
        requirePositive(streamTimeout, "Queue stream timeout");
        requirePositive(streamInterval, "Queue stream interval");
        requirePositive(safetyMargin, "Queue admission safety margin");

        Duration requiredEntryTtl = maxBacklogDrainDuration(
                maxExpectedBacklog,
                minimumAdmissionRatePerSecond
        ).plus(safetyMargin);
        if (entryTtl.compareTo(requiredEntryTtl) <= 0) {
            throw new IllegalArgumentException(
                    "Queue entry TTL must exceed the maximum backlog drain duration plus safety margin"
            );
        }
    }

    public double admissionsPerSecond() {
        return admissionsPerSecond(batchSize, fixedDelayMs);
    }

    public Duration maxBacklogDrainDuration() {
        return maxBacklogDrainDuration(maxExpectedBacklog, minimumAdmissionRatePerSecond);
    }

    private static double admissionsPerSecond(int batchSize, long fixedDelayMs) {
        return batchSize * 1_000.0 / fixedDelayMs;
    }

    private static Duration maxBacklogDrainDuration(
            int maxExpectedBacklog,
            double minimumAdmissionRatePerSecond
    ) {
        long drainMillis = (long) Math.ceil(maxExpectedBacklog / minimumAdmissionRatePerSecond * 1_000);
        return Duration.ofMillis(drainMillis);
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
