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
        CircuitBreaker circuitBreaker = circuitBreaker();
        EventRedisCacheAdapter adapter = adapter(cacheManager, circuitBreaker);

        assertThrows(EventCacheAccessException.class, adapter::findEventList);
        assertThrows(EventCacheAccessException.class, adapter::findEventList);

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        EventCacheAccessException rejected = assertThrows(EventCacheAccessException.class, adapter::findEventList);
        assertInstanceOf(CallNotPermittedException.class, rejected.getCause());
        verify(cacheManager, times(2)).getCache(EventCacheNames.EVENT_LIST);
    }

    @Test
    @DisplayName("OPEN 대기 후 HALF_OPEN probe가 성공하면 CLOSED로 복구한다")
    void recoversFromHalfOpenAfterSuccessfulRedisProbe() throws InterruptedException {
        CacheManager cacheManager = mock(CacheManager.class);
        Cache cache = mock(Cache.class);
        EventListCacheResponse expected = new EventListCacheResponse(List.of());
        when(cacheManager.getCache(EventCacheNames.EVENT_LIST)).thenReturn(cache);
        when(cache.get(any(), eq(EventListCacheResponse.class)))
                .thenThrow(new IllegalStateException("redis down"))
                .thenThrow(new IllegalStateException("redis down"))
                .thenReturn(expected);
        CircuitBreaker circuitBreaker = circuitBreaker();
        EventRedisCacheAdapter adapter = adapter(cacheManager, circuitBreaker);

        assertThrows(EventCacheAccessException.class, adapter::findEventList);
        assertThrows(EventCacheAccessException.class, adapter::findEventList);
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

        Thread.sleep(25);

        assertTrue(adapter.findEventList().isPresent());
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }

    private EventRedisCacheAdapter adapter(CacheManager cacheManager, CircuitBreaker circuitBreaker) {
        return new EventRedisCacheAdapter(cacheManager, mock(StringRedisTemplate.class), circuitBreaker);
    }

    private CircuitBreaker circuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(10))
                .permittedNumberOfCallsInHalfOpenState(1)
                .build();
        return CircuitBreaker.of("eventRedisCache", config);
    }
}
