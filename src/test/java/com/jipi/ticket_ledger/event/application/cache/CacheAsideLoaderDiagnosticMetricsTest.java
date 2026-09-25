package com.jipi.ticket_ledger.event.application.cache;

import com.jipi.ticket_ledger.global.observability.JdbcBorrowerRoleContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

class CacheAsideLoaderDiagnosticMetricsTest {

    private static final String DIAGNOSTIC_PROPERTY = "ticketledger.cache.diagnostic";

    @AfterEach
    void clearDiagnosticFlag() {
        System.clearProperty(DIAGNOSTIC_PROPERTY);
    }

    @Test
    void recordsOwnerLockTimingOnlyWhenDiagnosticModeIsEnabled() {
        System.setProperty(DIAGNOSTIC_PROPERTY, "true");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EventCache eventCache = mock(EventCache.class);
        when(eventCache.tryAcquireRefreshLock(eq("event:list::all"), org.mockito.ArgumentMatchers.anyString(), eq(Duration.ofSeconds(2))))
                .thenReturn(true);

        CacheAsideLoader loader = loader(eventCache, registry,
                new EventCachePolicyProperties(1, Duration.ofMillis(30), Duration.ofMillis(5),
                        Duration.ofSeconds(2), 1, 0.1, Duration.ofSeconds(1)));

        loader.load("event:list::all", Optional::<String>empty, () -> "value", value -> {});

        assertEquals(1, registry.get("ticketledger.event.cache.diagnostic.lock.duration")
                .tag("outcome", "owner").timer().count());
    }

    @Test
    void recordsFollowerTimeoutAndPollCount() {
        System.setProperty(DIAGNOSTIC_PROPERTY, "true");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EventCache eventCache = mock(EventCache.class);
        when(eventCache.tryAcquireRefreshLock(eq("event:list::all"), org.mockito.ArgumentMatchers.anyString(),
                eq(Duration.ofSeconds(2)))).thenReturn(false);

        EventCachePolicyProperties policy = new EventCachePolicyProperties(
                1, Duration.ofMillis(12), Duration.ofMillis(2), Duration.ofSeconds(2),
                1, 0.1, Duration.ofSeconds(1));
        CacheAsideLoader loader = loader(eventCache, registry, policy);

        assertThrows(RuntimeException.class, () -> loader.load(
                "event:list::all", Optional::<String>empty, () -> "unused", value -> {}));

        assertEquals(1, registry.get("ticketledger.event.cache.diagnostic.wait.duration")
                .tag("outcome", "timeout").timer().count());
        double polls = registry.get("ticketledger.event.cache.diagnostic.wait.polls")
                .tag("outcome", "timeout").summary().totalAmount();
        assertEquals(true, polls > 0);
    }

    private CacheAsideLoader loader(EventCache eventCache, SimpleMeterRegistry registry,
                                    EventCachePolicyProperties policy) {
        EventCacheMetrics metrics = new EventCacheMetrics(registry);
        return new CacheAsideLoader(eventCache, new CacheDatabaseLoadGuard(policy, metrics), policy, metrics,
                new JdbcBorrowerRoleContext(true));
    }
}
