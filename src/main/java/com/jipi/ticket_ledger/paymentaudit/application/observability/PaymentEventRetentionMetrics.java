package com.jipi.ticket_ledger.paymentaudit.application.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class PaymentEventRetentionMetrics {

    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> deletedCounters = new ConcurrentHashMap<>();
    private final AtomicLong inboxRecords = new AtomicLong();
    private final AtomicLong inboxOldestAgeSeconds = new AtomicLong();
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();

    public PaymentEventRetentionMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        meterRegistry.gauge("payment_audit_inbox_records", inboxRecords);
        meterRegistry.gauge("payment_audit_inbox_oldest_age_seconds", inboxOldestAgeSeconds);
        meterRegistry.gauge("payment_event_retention_last_success_timestamp_seconds", lastSuccessEpochSeconds);
    }

    public void recordDeleted(String store, int count) {
        deletedCounters.computeIfAbsent(store, key -> Counter.builder("payment_event_retention_deleted_total")
                        .tags(Tags.of("store", key))
                        .register(meterRegistry))
                .increment(count);
    }

    public void updateInbox(long count, long oldestAgeSeconds) {
        inboxRecords.set(count);
        inboxOldestAgeSeconds.set(oldestAgeSeconds);
    }

    public void recordSuccess(Instant now) {
        lastSuccessEpochSeconds.set(now.getEpochSecond());
    }
}
