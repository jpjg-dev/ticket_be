package com.jipi.ticket_ledger.event.application.cache;

import com.jipi.ticket_ledger.global.observability.JdbcBorrowerRoleContext;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import com.zaxxer.hikari.metrics.PoolStats;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheDiagnosticHikariConfigurationTest {

    @Test
    void delegatesMicrometerAndTagsAcquisitionAndHoldByTheirIntendedRole() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        JdbcBorrowerRoleContext context = new JdbcBorrowerRoleContext(true);
        PoolStats stats = new PoolStats(5_000) {
            @Override
            protected void update() {
            }
        };
        IMetricsTracker tracker = new CacheDiagnosticHikariConfiguration.DiagnosticTrackerFactory(
                registry, context
        ).create("HikariPool-1", stats);

        assertTrue(registry.getMeters().stream().anyMatch(meter -> meter.getId().getName().startsWith("hikaricp.")));

        try (JdbcBorrowerRoleContext.Scope root = context.openRoot(JdbcBorrowerRoleContext.HTTP_RESERVATION)) {
            tracker.recordConnectionAcquiredNanos(1_000_000);
            try (JdbcBorrowerRoleContext.Scope phase = context.openPhase(JdbcBorrowerRoleContext.JWT_USER_LOOKUP)) {
                tracker.recordConnectionAcquiredNanos(2_000_000);
                tracker.recordConnectionUsageMillis(20);
                tracker.recordConnectionTimeout();
            }
            tracker.recordConnectionUsageMillis(30);
        }

        assertEquals(1, registry.timer("ticketledger.jdbc.diagnostic.acquisition.attempt.wait",
                "role", JdbcBorrowerRoleContext.HTTP_RESERVATION).count());
        assertEquals(1, registry.timer("ticketledger.jdbc.diagnostic.acquisition.attempt.wait",
                "role", JdbcBorrowerRoleContext.JWT_USER_LOOKUP).count());
        assertEquals(2, registry.timer("ticketledger.jdbc.diagnostic.connection.hold",
                "role", JdbcBorrowerRoleContext.HTTP_RESERVATION).count());
        assertEquals(1, registry.counter("ticketledger.jdbc.diagnostic.acquisition.timeout",
                "role", JdbcBorrowerRoleContext.JWT_USER_LOOKUP).count());

        try (CacheDiagnosticContext.Scope cacheOwner = CacheDiagnosticContext.open("cache_owner")) {
            tracker.recordConnectionAcquiredNanos(3_000_000);
        }
        assertEquals(1, registry.timer("ticketledger.event.cache.diagnostic.jdbc.checkout",
                "role", "cache_owner").count());

        tracker.close();
        registry.close();
    }
}
