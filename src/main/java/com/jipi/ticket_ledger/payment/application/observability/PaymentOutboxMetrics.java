package com.jipi.ticket_ledger.payment.application.observability;

import com.jipi.ticket_ledger.payment.application.outbox.PaymentOutboxBacklogSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class PaymentOutboxMetrics {

    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> publishCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> failureCounters = new ConcurrentHashMap<>();
    private final Timer publishDuration;
    private final AtomicLong pendingCount = new AtomicLong();
    private final AtomicLong holdManualCount = new AtomicLong();
    private final AtomicLong activeLeaseCount = new AtomicLong();
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();

    public PaymentOutboxMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.publishDuration = Timer.builder("payment_outbox_publish_duration")
                .description("Outbox Kafka publish duration")
                .publishPercentileHistogram()
                .register(meterRegistry);

        meterRegistry.gauge("payment_outbox_backlog", Tags.of("status", "pending"), pendingCount);
        meterRegistry.gauge("payment_outbox_backlog", Tags.of("status", "hold_manual"), holdManualCount);
        meterRegistry.gauge("payment_outbox_active_leases", activeLeaseCount);
        meterRegistry.gauge("payment_outbox_oldest_pending_age_seconds", oldestPendingAgeSeconds);
    }

    public void recordPublish(String outcome, Duration duration) {
        publishCounters.computeIfAbsent(outcome, key -> Counter.builder("payment_outbox_publish_total")
                        .tag("outcome", key)
                        .register(meterRegistry))
                .increment();
        publishDuration.record(duration);
    }

    public void recordFailure(String kind) {
        failureCounters.computeIfAbsent(kind, key -> Counter.builder("payment_outbox_publish_failure_total")
                        .tag("kind", key)
                        .register(meterRegistry))
                .increment();
    }

    public void updateBacklog(PaymentOutboxBacklogSnapshot snapshot) {
        pendingCount.set(snapshot.pendingCount());
        holdManualCount.set(snapshot.holdManualCount());
        activeLeaseCount.set(snapshot.activeLeaseCount());
        oldestPendingAgeSeconds.set(snapshot.oldestPendingAgeSeconds());
    }
}
