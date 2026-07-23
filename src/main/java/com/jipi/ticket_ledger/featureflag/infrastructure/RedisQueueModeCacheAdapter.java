package com.jipi.ticket_ledger.featureflag.infrastructure;

import com.jipi.ticket_ledger.featureflag.application.QueueModeCache;
import com.jipi.ticket_ledger.featureflag.application.QueueModeCacheAccessException;
import com.jipi.ticket_ledger.featureflag.domain.QueueMode;
import com.jipi.ticket_ledger.featureflag.domain.QueueModeSnapshot;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.List;
import java.util.function.Supplier;

@Component
public class RedisQueueModeCacheAdapter implements QueueModeCache {

    private static final String QUEUE_MODE_CACHE_KEY = "ticketledger:feature-flag:queue-mode";
    private static final DefaultRedisScript<Long> PUT_IF_NEWER = new DefaultRedisScript<>(
            "local current = redis.call('get', KEYS[1]); "
                    + "if current then "
                    + "local separator = string.find(current, ':'); "
                    + "local currentVersion = tonumber(string.sub(current, 1, separator - 1)); "
                    + "if currentVersion > tonumber(ARGV[1]) then return 0 end; "
                    + "end; "
                    + "redis.call('set', KEYS[1], ARGV[1] .. ':' .. ARGV[2], 'PX', ARGV[3]); "
                    + "return 1;",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final CircuitBreaker queueModeRedisCacheReadCircuitBreaker;
    private final CircuitBreaker queueModeRedisCacheWriteCircuitBreaker;
    private final Duration cacheTtl;

    public RedisQueueModeCacheAdapter(
            StringRedisTemplate redisTemplate,
            @Qualifier("queueModeRedisCacheReadCircuitBreaker") CircuitBreaker queueModeRedisCacheReadCircuitBreaker,
            @Qualifier("queueModeRedisCacheWriteCircuitBreaker") CircuitBreaker queueModeRedisCacheWriteCircuitBreaker,
            QueueModeCacheProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.queueModeRedisCacheReadCircuitBreaker = queueModeRedisCacheReadCircuitBreaker;
        this.queueModeRedisCacheWriteCircuitBreaker = queueModeRedisCacheWriteCircuitBreaker;
        this.cacheTtl = properties.ttl();
    }

    @Override
    public Optional<QueueModeSnapshot> findQueueMode() {
        return executeRead(() -> Optional.ofNullable(redisTemplate.opsForValue().get(QUEUE_MODE_CACHE_KEY))
                .map(this::deserialize));
    }

    @Override
    public boolean putIfNewer(QueueModeSnapshot snapshot) {
        try {
            Long result = executeWrite(() -> redisTemplate.execute(
                    PUT_IF_NEWER,
                    List.of(QUEUE_MODE_CACHE_KEY),
                    Long.toString(snapshot.version()),
                    snapshot.queueMode().name(),
                    Long.toString(cacheTtl.toMillis())
            ));
            return Long.valueOf(1L).equals(result);
        } catch (QueueModeCacheAccessException exception) {
            openReadCircuit();
            throw exception;
        }
    }

    private <T> T executeRead(Supplier<T> operation) {
        return execute(queueModeRedisCacheReadCircuitBreaker, operation);
    }

    private <T> T executeWrite(Supplier<T> operation) {
        return execute(queueModeRedisCacheWriteCircuitBreaker, operation);
    }

    private <T> T execute(CircuitBreaker circuitBreaker, Supplier<T> operation) {
        try {
            return circuitBreaker.executeSupplier(operation);
        } catch (CallNotPermittedException exception) {
            throw new QueueModeCacheAccessException(exception);
        } catch (RuntimeException exception) {
            throw new QueueModeCacheAccessException(exception);
        }
    }

    private QueueModeSnapshot deserialize(String value) {
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new IllegalStateException("Invalid queue mode cache value");
        }
        long version = Long.parseLong(value.substring(0, separator));
        QueueMode mode = QueueMode.valueOf(value.substring(separator + 1));
        return new QueueModeSnapshot(mode, version);
    }

    private void openReadCircuit() {
        try {
            queueModeRedisCacheReadCircuitBreaker.transitionToOpenState();
        } catch (RuntimeException ignored) {
            // 이미 OPEN 계열 상태라면 런타임 조회는 동일하게 ENFORCED로 닫힌다.
        }
    }
}
