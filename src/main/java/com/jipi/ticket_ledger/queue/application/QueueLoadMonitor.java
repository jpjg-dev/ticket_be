package com.jipi.ticket_ledger.queue.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

@Component
public class QueueLoadMonitor {

    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Counter arrivals;
    private final Counter protectedRequests;
    private final AtomicInteger concurrentRequests = new AtomicInteger();
    private final AtomicLong totalArrivals = new AtomicLong();

    private long previousArrivals;
    private Instant previousSampleAt;

    public QueueLoadMonitor(MeterRegistry meterRegistry, Clock clock) {
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.arrivals = Counter.builder("queue_arrivals")
                .description("Requests arriving at the queue admission boundary")
                .register(meterRegistry);
        this.protectedRequests = Counter.builder("queue_protected_requests")
                .description("Reservation requests observed at the queue-protected boundary")
                .register(meterRegistry);
        Gauge.builder("queue_protected_requests_active", concurrentRequests, AtomicInteger::get)
                .description("Reservation requests currently running behind the queue boundary")
                .register(meterRegistry);
        this.previousSampleAt = clock.instant();
    }

    public void arrivalObserved() {
        totalArrivals.incrementAndGet();
        arrivals.increment();
    }

    public void reservationStarted() {
        concurrentRequests.incrementAndGet();
        protectedRequests.increment();
    }

    public void reservationFinished() {
        concurrentRequests.updateAndGet(current -> Math.max(0, current - 1));
    }

    public synchronized QueueLoadSnapshot sample(long waitingUsers) {
        Instant now = clock.instant();
        long currentArrivals = totalArrivals.get();
        double elapsedSeconds = Duration.between(previousSampleAt, now).toNanos() / 1_000_000_000.0;
        double requestRate = elapsedSeconds > 0
                ? (currentArrivals - previousArrivals) / elapsedSeconds
                : 0.0;

        previousArrivals = currentArrivals;
        previousSampleAt = now;

        double processCpu = gaugeValue("process.cpu.usage");
        double tomcatBusy = sumGauges("tomcat.threads.busy");
        double tomcatMax = sumGauges("tomcat.threads.config.max");
        double tomcatBusyRatio = tomcatMax > 0 ? tomcatBusy / tomcatMax : Double.NaN;
        double hikariPendingGauge = sumGauges("hikaricp.connections.pending");
        int hikariPending = Double.isFinite(hikariPendingGauge)
                ? (int) Math.ceil(Math.max(0.0, hikariPendingGauge))
                : 0;
        boolean telemetryComplete = Stream.of(processCpu, tomcatBusyRatio, hikariPendingGauge)
                .allMatch(Double::isFinite);

        return new QueueLoadSnapshot(
                requestRate,
                concurrentRequests.get(),
                processCpu,
                tomcatBusyRatio,
                hikariPending,
                waitingUsers,
                telemetryComplete
        );
    }

    private double gaugeValue(String name) {
        return meterRegistry.find(name).gauges().stream()
                .mapToDouble(Gauge::value)
                .filter(Double::isFinite)
                .findFirst()
                .orElse(Double.NaN);
    }

    private double sumGauges(String name) {
        var gauges = meterRegistry.find(name).gauges();
        if (gauges.isEmpty()) {
            return Double.NaN;
        }
        double[] values = gauges.stream()
                .mapToDouble(Gauge::value)
                .filter(Double::isFinite)
                .toArray();
        return values.length == 0
                ? Double.NaN
                : java.util.Arrays.stream(values).sum();
    }
}
