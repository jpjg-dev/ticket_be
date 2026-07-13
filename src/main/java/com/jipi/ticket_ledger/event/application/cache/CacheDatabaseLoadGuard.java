package com.jipi.ticket_ledger.event.application.cache;

import com.jipi.ticket_ledger.global.exception.CacheTemporarilyUnavailableException;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

@Component
public class CacheDatabaseLoadGuard {

    private final Semaphore permits;
    private final EventCachePolicyProperties policy;
    private final EventCacheMetrics metrics;

    public CacheDatabaseLoadGuard(EventCachePolicyProperties policy, EventCacheMetrics metrics) {
        this.policy = policy;
        this.metrics = metrics;
        this.permits = new Semaphore(policy.maxConcurrentDatabaseLoads(), true);
    }

    public <T> T execute(Supplier<T> loader) {
        if (!permits.tryAcquire()) {
            metrics.rejected();
            throw unavailable();
        }

        try {
            metrics.databaseLoad();
            return loader.get();
        } finally {
            permits.release();
        }
    }

    public CacheTemporarilyUnavailableException unavailable() {
        return new CacheTemporarilyUnavailableException(policy.retryAfterSeconds());
    }
}
