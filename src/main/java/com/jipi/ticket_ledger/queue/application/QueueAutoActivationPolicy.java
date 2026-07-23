package com.jipi.ticket_ledger.queue.application;

import java.time.Duration;

public record QueueAutoActivationPolicy(
        boolean enabled,
        LoadThreshold activate,
        LoadThreshold deactivate,
        int activationSamples,
        int deactivationSamples,
        Duration minimumEnforcedDuration,
        Duration cooldown
) {

    public record LoadThreshold(
            double requestRate,
            int concurrentRequests,
            double processCpu,
            double tomcatBusyRatio,
            int hikariPending
    ) {
    }
}
