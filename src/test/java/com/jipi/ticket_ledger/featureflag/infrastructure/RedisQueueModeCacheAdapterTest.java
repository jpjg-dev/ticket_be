package com.jipi.ticket_ledger.featureflag.infrastructure;

import com.jipi.ticket_ledger.featureflag.application.QueueModeCacheAccessException;
import com.jipi.ticket_ledger.featureflag.domain.QueueMode;
import com.jipi.ticket_ledger.featureflag.domain.QueueModeSnapshot;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisQueueModeCacheAdapterTest {

    private static final String CACHE_KEY = "ticketledger:feature-flag:queue-mode";
    private static final Duration CACHE_TTL = Duration.ofSeconds(5);

    @Test
    @DisplayName("큐 모드는 bounded TTL로 Redis에 저장한다")
    void storesQueueModeWithBoundedTtl() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(List.of(CACHE_KEY)), any(Object[].class)))
                .thenReturn(1L);
        RedisQueueModeCacheAdapter adapter = adapter(redisTemplate, CircuitBreaker.ofDefaults("read"), CircuitBreaker.ofDefaults("write"));

        boolean stored = adapter.putIfNewer(new QueueModeSnapshot(QueueMode.ENFORCED, 3L));

        assertTrue(stored);
        verify(redisTemplate).execute(any(DefaultRedisScript.class), eq(List.of(CACHE_KEY)),
                eq("3"), eq("ENFORCED"), eq("5000"));
    }

    @Test
    @DisplayName("더 최신 버전이 있으면 오래된 모드는 덮어쓰지 않는다")
    void rejectsOlderSnapshot() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(List.of(CACHE_KEY)), any(Object[].class)))
                .thenReturn(0L);
        RedisQueueModeCacheAdapter adapter = adapter(redisTemplate,
                CircuitBreaker.ofDefaults("read"), CircuitBreaker.ofDefaults("write"));

        assertFalse(adapter.putIfNewer(new QueueModeSnapshot(QueueMode.OFF, 2L)));
    }

    @Test
    @DisplayName("cache 갱신 실패는 읽기 회로를 열어 런타임 판단을 ENFORCED로 닫는다")
    void writeFailureOpensReadCircuit() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        CircuitBreaker readCircuitBreaker = CircuitBreaker.ofDefaults("read");
        CircuitBreaker writeCircuitBreaker = CircuitBreaker.ofDefaults("write");
        writeCircuitBreaker.transitionToForcedOpenState();
        RedisQueueModeCacheAdapter adapter = adapter(redisTemplate, readCircuitBreaker, writeCircuitBreaker);

        assertThrows(QueueModeCacheAccessException.class,
                () -> adapter.putIfNewer(new QueueModeSnapshot(QueueMode.OFF, 4L)));

        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN, readCircuitBreaker.getState());
    }

    private RedisQueueModeCacheAdapter adapter(
            StringRedisTemplate redisTemplate,
            CircuitBreaker readCircuitBreaker,
            CircuitBreaker writeCircuitBreaker
    ) {
        return new RedisQueueModeCacheAdapter(
                redisTemplate,
                readCircuitBreaker,
                writeCircuitBreaker,
                new QueueModeCacheProperties(CACHE_TTL)
        );
    }
}
