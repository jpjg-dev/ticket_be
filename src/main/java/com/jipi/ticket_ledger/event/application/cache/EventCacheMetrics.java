package com.jipi.ticket_ledger.event.application.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
public class EventCacheMetrics {

    private static final String METRIC_NAME = "ticketledger.event.cache.requests";

    private final MeterRegistry meterRegistry;
    private final boolean diagnosticEnabled = Boolean.getBoolean("ticketledger.cache.diagnostic");

    public EventCacheMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void hit() {
        increment("hit");
    }

    public void miss() {
        increment("miss");
    }

    public void redisError() {
        increment("redis_error");
    }

    public void databaseLoad() {
        increment("database_load");
    }

    public void rejected(String reason) {
        increment("rejected");
        meterRegistry.counter("ticketledger.event.cache.rejections", "reason", reason).increment();
    }

    public void refreshPhase(String phase, long startedAtNanos) {
        meterRegistry.timer("ticketledger.event.cache.refresh.duration", "phase", phase)
                .record(System.nanoTime() - startedAtNanos, TimeUnit.NANOSECONDS);
    }

    public void refreshLock(String outcome, long startedAtNanos) {
        if (!diagnosticEnabled) return;
        meterRegistry.timer("ticketledger.event.cache.diagnostic.lock.duration", "outcome", outcome)
                .record(System.nanoTime() - startedAtNanos, TimeUnit.NANOSECONDS);
    }

    public void refreshWait(String outcome, long startedAtNanos, int polls) {
        if (!diagnosticEnabled) return;
        meterRegistry.timer("ticketledger.event.cache.diagnostic.wait.duration", "outcome", outcome)
                .record(System.nanoTime() - startedAtNanos, TimeUnit.NANOSECONDS);
        meterRegistry.summary("ticketledger.event.cache.diagnostic.wait.polls", "outcome", outcome)
                .record(polls);
    }

    private void increment(String outcome) {
        meterRegistry.counter(METRIC_NAME, "outcome", outcome).increment();
    }
}
