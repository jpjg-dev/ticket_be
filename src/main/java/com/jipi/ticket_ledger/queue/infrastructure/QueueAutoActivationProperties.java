package com.jipi.ticket_ledger.queue.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("queue.auto-activation")
public record QueueAutoActivationProperties(
        boolean enabled,
        long fixedDelayMs,
        LoadThreshold activate,
        LoadThreshold deactivate,
        int activationSamples,
        int deactivationSamples,
        Duration minimumEnforcedDuration,
        Duration cooldown
) {

    public QueueAutoActivationProperties {
        Objects.requireNonNull(activate, "Queue activation threshold must not be null");
        Objects.requireNonNull(deactivate, "Queue deactivation threshold must not be null");
        Objects.requireNonNull(minimumEnforcedDuration, "Minimum enforced duration must not be null");
        Objects.requireNonNull(cooldown, "Queue activation cooldown must not be null");
        if (fixedDelayMs <= 0 || activationSamples <= 0 || deactivationSamples <= 0) {
            throw new IllegalArgumentException("Queue auto-activation intervals and sample counts must be positive");
        }
        if (minimumEnforcedDuration.isNegative() || cooldown.isNegative()) {
            throw new IllegalArgumentException("Queue auto-activation durations must not be negative");
        }
        if (!deactivate.isLessThanOrEqualTo(activate)) {
            throw new IllegalArgumentException(
                    "Queue deactivation thresholds must not exceed activation thresholds"
            );
        }
    }

    public record LoadThreshold(
            double requestRate,
            int concurrentRequests,
            double processCpu,
            double tomcatBusyRatio,
            int hikariPending
    ) {
        public LoadThreshold {
            if (requestRate < 0 || concurrentRequests < 0 || hikariPending < 0) {
                throw new IllegalArgumentException("Queue load thresholds must not be negative");
            }
            if (processCpu < 0 || processCpu > 1 || tomcatBusyRatio < 0 || tomcatBusyRatio > 1) {
                throw new IllegalArgumentException("Queue utilization thresholds must be between 0 and 1");
            }
        }

        private boolean isLessThanOrEqualTo(LoadThreshold other) {
            return requestRate <= other.requestRate
                    && concurrentRequests <= other.concurrentRequests
                    && processCpu <= other.processCpu
                    && tomcatBusyRatio <= other.tomcatBusyRatio
                    && hikariPending <= other.hikariPending;
        }
    }
}
