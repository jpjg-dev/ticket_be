package com.jipi.ticket_ledger.event.application.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class CacheAsideLoader {

    private final EventCache eventCache;
    private final CacheDatabaseLoadGuard databaseLoadGuard;
    private final EventCachePolicyProperties policy;
    private final EventCacheMetrics metrics;

    public <T> T load(String key, Supplier<Optional<T>> cacheReader, Supplier<T> databaseReader,
                      Consumer<T> cacheWriter) {
        try {
            Optional<T> cached = cacheReader.get();
            if (cached.isPresent()) {
                metrics.hit();
                return cached.get();
            }
            metrics.miss();
            return loadOnCacheMiss(key, cacheReader, databaseReader, cacheWriter);
        } catch (EventCacheAccessException exception) {
            metrics.redisError();
            return databaseLoadGuard.execute(databaseReader);
        }
    }

    private <T> T loadOnCacheMiss(String key, Supplier<Optional<T>> cacheReader, Supplier<T> databaseReader,
                                  Consumer<T> cacheWriter) {
        String token = UUID.randomUUID().toString();
        long lockStarted = System.nanoTime();
        boolean acquired;
        try {
            acquired = eventCache.tryAcquireRefreshLock(key, token, policy.refreshLockTtl());
        } catch (EventCacheAccessException exception) {
            metrics.refreshLock("redis_error", lockStarted);
            throw exception;
        }
        metrics.refreshLock(acquired ? "owner" : "follower", lockStarted);
        if (!acquired) {
            return waitForRefresh(cacheReader);
        }

        try {
            Optional<T> refreshed = cacheReader.get();
            if (refreshed.isPresent()) {
                return refreshed.get();
            }

            long databaseStarted = System.nanoTime();
            T value;
            try {
                value = databaseLoadGuard.execute(databaseReader);
            } finally {
                metrics.refreshPhase("database_load", databaseStarted);
            }
            writeCache(cacheWriter, value);
            return value;
        } finally {
            releaseLock(key, token);
        }
    }

    private <T> T waitForRefresh(Supplier<Optional<T>> cacheReader) {
        long waitStarted = System.nanoTime();
        int polls = 0;
        Instant deadline = Instant.now().plus(policy.refreshWaitTimeout());
        try {
            while (Instant.now().isBefore(deadline)) {
                try {
                    Thread.sleep(policy.refreshRetryInterval());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    metrics.refreshWait("interrupted", waitStarted, polls);
                    metrics.rejected("interrupted");
                    throw databaseLoadGuard.unavailable();
                }
                polls++;
                Optional<T> cached = cacheReader.get();
                if (cached.isPresent()) {
                    metrics.refreshWait("hit", waitStarted, polls);
                    return cached.get();
                }
            }
        } catch (EventCacheAccessException exception) {
            metrics.refreshWait("redis_error", waitStarted, polls);
            throw exception;
        }
        metrics.refreshWait("timeout", waitStarted, polls);
        metrics.rejected("refresh_timeout");
        throw databaseLoadGuard.unavailable();
    }

    private <T> void writeCache(Consumer<T> cacheWriter, T value) {
        long writeStarted = System.nanoTime();
        try {
            cacheWriter.accept(value);
        } catch (EventCacheAccessException exception) {
            metrics.redisError();
        } finally {
            metrics.refreshPhase("cache_write", writeStarted);
        }
    }

    private void releaseLock(String key, String token) {
        try {
            eventCache.releaseRefreshLock(key, token);
        } catch (EventCacheAccessException exception) {
            metrics.redisError();
        }
    }

}
