package com.jipi.ticket_ledger.event.application.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class EventCacheMetrics {

    private static final String METRIC_NAME = "ticketledger.event.cache.requests";

    private final MeterRegistry meterRegistry;

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

    private void increment(String outcome) {
        meterRegistry.counter(METRIC_NAME, "outcome", outcome).increment();
    }
}
