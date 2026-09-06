package com.jipi.ticket_ledger.event.application.cache;

import com.jipi.ticket_ledger.global.exception.CacheTemporarilyUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CacheAsideLoaderRejectionTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final EventCacheMetrics metrics = new EventCacheMetrics(registry);
    private final EventCachePolicyProperties policy = new EventCachePolicyProperties(
            1, Duration.ofMillis(30), Duration.ofMillis(5), Duration.ofSeconds(2),
            1, 0.1, Duration.ofSeconds(1));
    private final CacheAsideLoader loader = new CacheAsideLoader(mock(EventCache.class),
            new CacheDatabaseLoadGuard(policy, metrics), policy, metrics);

    @Test
    void recordsRefreshTimeoutWithoutStartingAnotherDatabaseLoad() {
        assertThrows(CacheTemporarilyUnavailableException.class, () -> loader.load(
                "event:list::all", Optional::<String>empty,
                () -> { throw new AssertionError("Refresh follower must not query DB"); }, value -> {}));
        assertEquals(1, registry.get("ticketledger.event.cache.rejections")
                .tag("reason", "refresh_timeout").counter().count());
        assertEquals(1, registry.get("ticketledger.event.cache.requests")
                .tag("outcome", "rejected").counter().count());
        assertNull(registry.find("ticketledger.event.cache.rejections")
                .tag("reason", "database_capacity").counter());
    }

    @Test
    void recordsInterruptionAndPreservesInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CacheTemporarilyUnavailableException.class, () -> loader.load(
                    "event:list::all", Optional::<String>empty, () -> "unused", value -> {}));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, registry.get("ticketledger.event.cache.rejections")
                    .tag("reason", "interrupted").counter().count());
        } finally {
            Thread.interrupted();
        }
    }
}
