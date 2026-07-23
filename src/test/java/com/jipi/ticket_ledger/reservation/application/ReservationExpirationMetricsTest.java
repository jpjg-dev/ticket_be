package com.jipi.ticket_ledger.reservation.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReservationExpirationMetricsTest {

    @Test
    void recordsOutcomeByTrigger() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReservationExpirationMetrics metrics = new ReservationExpirationMetrics(registry);

        metrics.record("SCHEDULER", "expired");
        metrics.record("SCHEDULER", "failed");

        assertEquals(1.0, registry.get("reservation_expiration_group_total")
                .tags("trigger", "scheduler", "outcome", "expired").counter().count());
        assertEquals(1.0, registry.get("reservation_expiration_group_total")
                .tags("trigger", "scheduler", "outcome", "failed").counter().count());
    }
}
