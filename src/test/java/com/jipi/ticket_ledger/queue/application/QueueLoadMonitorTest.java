package com.jipi.ticket_ledger.queue.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueueLoadMonitorTest {

    @Test
    void reportsCompleteTelemetryOnlyWhenAllRequiredGaugesAreFinite() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        registerGauge(meterRegistry, "process.cpu.usage", 0.2);
        registerGauge(meterRegistry, "tomcat.threads.busy", 2.0);
        registerGauge(meterRegistry, "tomcat.threads.config.max", 10.0);
        registerGauge(meterRegistry, "hikaricp.connections.pending", 0.0);
        QueueLoadMonitor monitor = new QueueLoadMonitor(
                meterRegistry,
                Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC)
        );

        assertTrue(monitor.sample(0).telemetryComplete());
    }

    @Test
    void reportsIncompleteTelemetryWhenRequiredGaugeIsMissing() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        registerGauge(meterRegistry, "process.cpu.usage", 0.2);
        registerGauge(meterRegistry, "tomcat.threads.busy", 2.0);
        registerGauge(meterRegistry, "tomcat.threads.config.max", 10.0);
        QueueLoadMonitor monitor = new QueueLoadMonitor(
                meterRegistry,
                Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC)
        );

        assertFalse(monitor.sample(0).telemetryComplete());
    }

    private void registerGauge(SimpleMeterRegistry meterRegistry, String name, double value) {
        AtomicReference<Double> holder = new AtomicReference<>(value);
        Gauge.builder(name, holder, AtomicReference::get).register(meterRegistry);
    }
}
