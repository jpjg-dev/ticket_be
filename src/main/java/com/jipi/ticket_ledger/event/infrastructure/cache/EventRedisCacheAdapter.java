package com.jipi.ticket_ledger.event.infrastructure.cache;

import com.jipi.ticket_ledger.event.application.cache.EventCache;
import com.jipi.ticket_ledger.event.application.cache.EventCacheAccessException;
import com.jipi.ticket_ledger.event.application.model.EventDetailResponse;
import com.jipi.ticket_ledger.event.application.model.EventListCacheResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class EventRedisCacheAdapter implements EventCache {

    private static final String EVENT_LIST_KEY = "all";
    private static final String LOCK_PREFIX = "ticketledger:cache:refresh-lock:";
    private static final DefaultRedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

    private final CacheManager cacheManager;
    private final StringRedisTemplate redisTemplate;
    private final CircuitBreaker eventRedisCacheCircuitBreaker;

    @Override
    public Optional<EventListCacheResponse> findEventList() {
        return get(EventCacheNames.EVENT_LIST, EVENT_LIST_KEY, EventListCacheResponse.class);
    }

    @Override
    public Optional<EventDetailResponse> findEventDetail(Long eventId) {
        return get(EventCacheNames.EVENT_DETAIL, eventId, EventDetailResponse.class);
    }

    @Override
    public void putEventList(EventListCacheResponse response) {
        put(EventCacheNames.EVENT_LIST, EVENT_LIST_KEY, response);
    }

    @Override
    public void putEventDetail(Long eventId, EventDetailResponse response) {
        put(EventCacheNames.EVENT_DETAIL, eventId, response);
    }

    @Override
    public boolean tryAcquireRefreshLock(String key, String token, Duration ttl) {
        return executeRedisOperation(() -> Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(lockKey(key), token, ttl)));
    }

    @Override
    public void releaseRefreshLock(String key, String token) {
        executeRedisOperation(() -> {
            redisTemplate.execute(RELEASE_LOCK, List.of(lockKey(key)), token);
            return null;
        });
    }

    private <T> Optional<T> get(String cacheName, Object key, Class<T> type) {
        return executeRedisOperation(() -> Optional.ofNullable(cache(cacheName).get(key, type)));
    }

    private void put(String cacheName, Object key, Object value) {
        executeRedisOperation(() -> {
            cache(cacheName).put(key, value);
            return null;
        });
    }

    private <T> T executeRedisOperation(Supplier<T> operation) {
        try {
            return eventRedisCacheCircuitBreaker.executeSupplier(operation);
        } catch (CallNotPermittedException exception) {
            throw new EventCacheAccessException(exception);
        } catch (RuntimeException exception) {
            throw new EventCacheAccessException(exception);
        }
    }

    private Cache cache(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            throw new IllegalStateException("캐시 설정을 찾을 수 없습니다: " + cacheName);
        }
        return cache;
    }

    private String lockKey(String key) {
        return LOCK_PREFIX + key;
    }
}
