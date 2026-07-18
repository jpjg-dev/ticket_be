package com.jipi.ticket_ledger.event.infrastructure.cache;

import com.jipi.ticket_ledger.event.application.cache.EventCacheAccessException;
import com.jipi.ticket_ledger.event.application.model.EventListCacheResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventRedisCacheAdapterCircuitBreakerTest {

    @Test
    @DisplayName("Redis 실패가 임계치를 넘으면 CLOSED에서 OPEN으로 전이하고 이후 호출을 차단한다")
    void opensAndFailsFastAfterRedisFailures() {
        CacheManager cacheManager = mock(CacheManager.class);
        Cache cache = mock(Cache.class);
        when(cacheManager.getCache(EventCacheNames.EVENT_LIST)).thenReturn(cache);
        when(cache.get(any(), eq(EventListCacheResponse.class))).thenThrow(new IllegalStateException("redis down"));
        CircuitBreaker readCircuitBreaker = circuitBreaker("eventRedisCacheRead");
        EventRedisCacheAdapter adapter = adapter(cacheManager, readCircuitBreaker, circuitBreaker("eventRedisCacheWrite"));

        assertThrows(EventCacheAccessException.class, adapter::findEventList);
        assertThrows(EventCacheAccessException.class, adapter::findEventList);

        assertEquals(CircuitBreaker.State.OPEN, readCircuitBreaker.getState());
        EventCacheAccessException rejected = assertThrows(EventCacheAccessException.class, adapter::findEventList);
        assertInstanceOf(CallNotPermittedException.class, rejected.getCause());
        verify(cacheManager, times(2)).getCache(EventCacheNames.EVENT_LIST);
    }

    @Test
    @DisplayName("OPEN 대기 후 HALF_OPEN probe가 성공하면 CLOSED로 복구한다")
    void recoversFromHalfOpenAfterSuccessfulRedisProbe() {
        CacheManager cacheManager = mock(CacheManager.class);
        Cache cache = mock(Cache.class);
        EventListCacheResponse expected = new EventListCacheResponse(List.of());
        when(cacheManager.getCache(EventCacheNames.EVENT_LIST)).thenReturn(cache);
        when(cache.get(any(), eq(EventListCacheResponse.class)))
                .thenThrow(new IllegalStateException("redis down"))
                .thenThrow(new IllegalStateException("redis down"))
                .thenReturn(expected);
        CircuitBreaker readCircuitBreaker = circuitBreaker("eventRedisCacheRead");
        EventRedisCacheAdapter adapter = adapter(cacheManager, readCircuitBreaker, circuitBreaker("eventRedisCacheWrite"));

        assertThrows(EventCacheAccessException.class, adapter::findEventList);
        assertThrows(EventCacheAccessException.class, adapter::findEventList);
        assertEquals(CircuitBreaker.State.OPEN, readCircuitBreaker.getState());

        readCircuitBreaker.transitionToHalfOpenState();

        assertTrue(adapter.findEventList().isPresent());
        assertEquals(CircuitBreaker.State.CLOSED, readCircuitBreaker.getState());
    }

    @Test
    @DisplayName("읽기 회로가 열려도 캐시 쓰기 회로는 독립적으로 동작한다")
    void readCircuitDoesNotBlockCacheWrites() {
        CacheManager cacheManager = mock(CacheManager.class);
        Cache cache = mock(Cache.class);
        when(cacheManager.getCache(EventCacheNames.EVENT_LIST)).thenReturn(cache);
        CircuitBreaker readCircuitBreaker = circuitBreaker("eventRedisCacheRead");
        CircuitBreaker writeCircuitBreaker = circuitBreaker("eventRedisCacheWrite");
        readCircuitBreaker.transitionToForcedOpenState();
        EventRedisCacheAdapter adapter = adapter(cacheManager, readCircuitBreaker, writeCircuitBreaker);
        EventListCacheResponse response = new EventListCacheResponse(List.of());

        adapter.putEventList(response);

        verify(cache).put("all", response);
        assertEquals(CircuitBreaker.State.CLOSED, writeCircuitBreaker.getState());
    }

    @Test
    @DisplayName("쓰기 회로가 열려도 기존 캐시 조회는 계속 동작한다")
    void writeCircuitDoesNotBlockCacheReads() {
        CacheManager cacheManager = mock(CacheManager.class);
        Cache cache = mock(Cache.class);
        EventListCacheResponse response = new EventListCacheResponse(List.of());
        when(cacheManager.getCache(EventCacheNames.EVENT_LIST)).thenReturn(cache);
        when(cache.get(any(), eq(EventListCacheResponse.class))).thenReturn(response);
        CircuitBreaker readCircuitBreaker = circuitBreaker("eventRedisCacheRead");
        CircuitBreaker writeCircuitBreaker = circuitBreaker("eventRedisCacheWrite");
        writeCircuitBreaker.transitionToForcedOpenState();
        EventRedisCacheAdapter adapter = adapter(cacheManager, readCircuitBreaker, writeCircuitBreaker);

        Optional<EventListCacheResponse> cached = adapter.findEventList();

        assertTrue(cached.isPresent());
        assertEquals(CircuitBreaker.State.CLOSED, readCircuitBreaker.getState());
    }

    private EventRedisCacheAdapter adapter(CacheManager cacheManager, CircuitBreaker readCircuitBreaker,
                                           CircuitBreaker writeCircuitBreaker) {
        return new EventRedisCacheAdapter(cacheManager, mock(StringRedisTemplate.class),
                readCircuitBreaker, writeCircuitBreaker);
    }

    private CircuitBreaker circuitBreaker(String name) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .permittedNumberOfCallsInHalfOpenState(1)
                .build();
        return CircuitBreaker.of(name, config);
    }
}
