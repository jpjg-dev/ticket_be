package com.jipi.ticket_ledger.queue.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class QueueAdmissionMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicLong waitingUsers = new AtomicLong();
    private final AtomicInteger activeSseConnections = new AtomicInteger();

    public QueueAdmissionMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("queue_waiting_users", waitingUsers, AtomicLong::get)
                .description("Current users waiting for queue admission")
                .register(meterRegistry);
        Gauge.builder("queue_sse_connections_active", activeSseConnections, AtomicInteger::get)
                .description("Current active queue SSE connections")
                .register(meterRegistry);
    }

    public void record(String mode, String outcome) {
        Counter.builder("queue_admission_total")
                .description("Queue admission decisions by feature flag mode")
                .tag("mode", mode)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    public void recordAdmitted(int count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("queue_admitted_total")
                .description("Users promoted from waiting to admitted")
                .register(meterRegistry)
                .increment(count);
    }

    public void recordToken(String outcome) {
        Counter.builder("queue_token_total")
                .description("Queue admission token outcomes")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    public void recordObservedWait(Duration duration) {
        Timer.builder("queue_wait_duration")
                .description("Queue wait observed through an SSE connection")
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(duration);
    }

    public void setWaitingUsers(long count) {
        waitingUsers.set(Math.max(0L, count));
    }

    public void streamOpened() {
        activeSseConnections.incrementAndGet();
    }

    public void streamClosed() {
        activeSseConnections.updateAndGet(current -> Math.max(0, current - 1));
    }
}
