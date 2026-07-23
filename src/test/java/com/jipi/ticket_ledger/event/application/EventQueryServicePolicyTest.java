package com.jipi.ticket_ledger.event.application;

import com.jipi.ticket_ledger.event.application.cache.CacheDatabaseLoadGuard;
import com.jipi.ticket_ledger.event.application.cache.CacheAsideLoader;
import com.jipi.ticket_ledger.event.application.cache.EventCache;
import com.jipi.ticket_ledger.event.application.cache.EventCacheAccessException;
import com.jipi.ticket_ledger.event.application.cache.EventCacheMetrics;
import com.jipi.ticket_ledger.event.application.cache.EventCachePolicyProperties;
import com.jipi.ticket_ledger.event.application.model.EventListCacheResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventQueryServicePolicyTest {

    @Test
    @DisplayName("Redis 조회가 실패하면 제한된 DB fallback으로 공연 목록을 반환한다")
    void fallsBackToDatabaseWhenRedisIsUnavailable() {
        EventCache cache = mock(EventCache.class);
        EventDatabaseReader databaseReader = mock(EventDatabaseReader.class);
        EventCachePolicyProperties policy = new EventCachePolicyProperties(2, Duration.ofMillis(300),
                Duration.ofMillis(20), Duration.ofSeconds(2), 1, 0.1, Duration.ofSeconds(1));
        EventCacheMetrics metrics = new EventCacheMetrics(new SimpleMeterRegistry());
        CacheDatabaseLoadGuard guard = new CacheDatabaseLoadGuard(policy, metrics);
        EventQueryService service = new EventQueryService(cache, databaseReader,
                new CacheAsideLoader(cache, guard, policy, metrics));
        EventListCacheResponse expected = new EventListCacheResponse(List.of());

        when(cache.findEventList()).thenThrow(new EventCacheAccessException(new IllegalStateException("redis down")));
        when(databaseReader.getEvents()).thenReturn(expected);

        assertEquals(expected, service.getEvents());
        verify(databaseReader).getEvents();
    }
}
