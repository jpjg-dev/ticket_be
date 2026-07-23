package com.jipi.ticket_ledger.queue.application;

import com.jipi.ticket_ledger.featureflag.application.FeatureFlagService;
import com.jipi.ticket_ledger.featureflag.domain.QueueMode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class QueueAutoActivationScheduler {

    private final FeatureFlagService featureFlagService;
    private final QueueAutoActivationManager autoActivationManager;
    private final QueueLoadMonitor loadMonitor;
    private final QueueAdmissionMetrics admissionMetrics;

    @Scheduled(
            fixedDelayString = "${queue.auto-activation.fixed-delay-ms}",
            scheduler = "queueAutoActivationTaskScheduler"
    )
    public void evaluate() {
        QueueMode configuredMode = featureFlagService.getQueueModeForRuntime();
        QueueLoadSnapshot snapshot = loadMonitor.sample(admissionMetrics.waitingUsers());
        autoActivationManager.evaluate(configuredMode, snapshot);
    }
}
