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
        if (!eventCache.tryAcquireRefreshLock(key, token, policy.refreshLockTtl())) {
            return waitForRefresh(cacheReader);
        }

        try {
            Optional<T> refreshed = cacheReader.get();
            if (refreshed.isPresent()) {
                return refreshed.get();
            }

            T value = databaseLoadGuard.execute(databaseReader);
            writeCache(cacheWriter, value);
            return value;
        } finally {
            releaseLock(key, token);
        }
    }

    private <T> T waitForRefresh(Supplier<Optional<T>> cacheReader) {
        Instant deadline = Instant.now().plus(policy.refreshWaitTimeout());
        while (Instant.now().isBefore(deadline)) {
            sleep(policy.refreshRetryInterval());
            Optional<T> cached = cacheReader.get();
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        metrics.rejected();
        throw databaseLoadGuard.unavailable();
    }

    private <T> void writeCache(Consumer<T> cacheWriter, T value) {
        try {
            cacheWriter.accept(value);
        } catch (EventCacheAccessException exception) {
            metrics.redisError();
        }
    }

    private void releaseLock(String key, String token) {
        try {
            eventCache.releaseRefreshLock(key, token);
        } catch (EventCacheAccessException exception) {
            metrics.redisError();
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw databaseLoadGuard.unavailable();
        }
    }
}
