package com.jipi.ticket_ledger.queue.application;

import com.jipi.ticket_ledger.featureflag.domain.QueueMode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class QueueAutoActivationManager {

    private final QueueAutoActivationPolicy policy;
    private final Clock clock;
    private final Counter transitions;
    private final AtomicInteger autoEnforcedGauge = new AtomicInteger();

    private volatile boolean autoEnforced;
    private int overloadedSamples;
    private int recoveredSamples;
    private Instant lastTransitionAt;

    public QueueAutoActivationManager(
            QueueAutoActivationPolicy policy,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.policy = policy;
        this.clock = clock;
        this.transitions = Counter.builder("queue_auto_activation_transitions")
                .description("Automatic queue activation state transitions")
                .register(meterRegistry);
        Gauge.builder("queue_auto_enforced", autoEnforcedGauge, AtomicInteger::get)
                .description("Whether SHADOW mode currently enforces queue admission automatically")
                .register(meterRegistry);
        this.autoEnforced = policy.enabled();
        this.autoEnforcedGauge.set(policy.enabled() ? 1 : 0);
        this.lastTransitionAt = clock.instant();
    }

    public QueueMode resolve(QueueMode configuredMode) {
        if (!policy.enabled() || configuredMode != QueueMode.SHADOW) {
            return configuredMode;
        }
        return autoEnforced ? QueueMode.ENFORCED : QueueMode.SHADOW;
    }

    public synchronized void evaluate(QueueMode configuredMode, QueueLoadSnapshot snapshot) {
        if (!policy.enabled() || configuredMode == QueueMode.OFF) {
            resetAutomaticState();
            return;
        }
        if (configuredMode == QueueMode.ENFORCED) {
            overloadedSamples = 0;
            recoveredSamples = 0;
            return;
        }

        Instant now = clock.instant();
        if (!autoEnforced) {
            recoveredSamples = 0;
            overloadedSamples = isOverloaded(snapshot) ? overloadedSamples + 1 : 0;
            if (overloadedSamples >= policy.activationSamples()
                    && elapsedSinceTransition(now).compareTo(policy.cooldown()) >= 0) {
                transition(true, now, snapshot, "threshold_exceeded");
            }
            return;
        }

        overloadedSamples = 0;
        recoveredSamples = isRecovered(snapshot) ? recoveredSamples + 1 : 0;
        if (recoveredSamples >= policy.deactivationSamples()
                && elapsedSinceTransition(now).compareTo(policy.minimumEnforcedDuration()) >= 0
                && elapsedSinceTransition(now).compareTo(policy.cooldown()) >= 0) {
            transition(false, now, snapshot, "load_recovered");
        }
    }

    public boolean isAutoEnforced() {
        return autoEnforced;
    }

    public boolean isAutomaticControlEnabled() {
        return policy.enabled();
    }

    private boolean isOverloaded(QueueLoadSnapshot snapshot) {
        QueueAutoActivationPolicy.LoadThreshold threshold = policy.activate();
        return !snapshot.telemetryComplete()
                || snapshot.requestRate() >= threshold.requestRate()
                || snapshot.concurrentRequests() >= threshold.concurrentRequests()
                || exceedsIfAvailable(snapshot.processCpu(), threshold.processCpu())
                || exceedsIfAvailable(snapshot.tomcatBusyRatio(), threshold.tomcatBusyRatio())
                || snapshot.hikariPending() >= threshold.hikariPending();
    }

    private boolean isRecovered(QueueLoadSnapshot snapshot) {
        QueueAutoActivationPolicy.LoadThreshold threshold = policy.deactivate();
        return snapshot.telemetryComplete()
                && snapshot.waitingUsers() == 0
                && snapshot.requestRate() <= threshold.requestRate()
                && snapshot.concurrentRequests() <= threshold.concurrentRequests()
                && belowIfAvailable(snapshot.processCpu(), threshold.processCpu())
                && belowIfAvailable(snapshot.tomcatBusyRatio(), threshold.tomcatBusyRatio())
                && snapshot.hikariPending() <= threshold.hikariPending();
    }

    private boolean exceedsIfAvailable(double value, double threshold) {
        return Double.isFinite(value) && value >= threshold;
    }

    private boolean belowIfAvailable(double value, double threshold) {
        return Double.isFinite(value) && value <= threshold;
    }

    private Duration elapsedSinceTransition(Instant now) {
        return Duration.between(lastTransitionAt, now);
    }

    private void transition(boolean enforced, Instant now, QueueLoadSnapshot snapshot, String reason) {
        autoEnforced = enforced;
        autoEnforcedGauge.set(enforced ? 1 : 0);
        overloadedSamples = 0;
        recoveredSamples = 0;
        lastTransitionAt = now;
        transitions.increment();
        log.warn("queue auto-activation transition effectiveMode={} reason={} requestRate={} concurrentRequests={} processCpu={} tomcatBusyRatio={} hikariPending={} waitingUsers={} telemetryComplete={}",
                enforced ? QueueMode.ENFORCED : QueueMode.SHADOW,
                reason,
                snapshot.requestRate(),
                snapshot.concurrentRequests(),
                snapshot.processCpu(),
                snapshot.tomcatBusyRatio(),
                snapshot.hikariPending(),
                snapshot.waitingUsers(),
                snapshot.telemetryComplete());
    }

    private void resetAutomaticState() {
        overloadedSamples = 0;
        recoveredSamples = 0;
        autoEnforced = false;
        autoEnforcedGauge.set(0);
    }
}
