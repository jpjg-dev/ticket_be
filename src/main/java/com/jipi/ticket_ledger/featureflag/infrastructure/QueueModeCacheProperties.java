package com.jipi.ticket_ledger.featureflag.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("cache.featureflag.queue.mode")
public record QueueModeCacheProperties(Duration ttl) {

    public QueueModeCacheProperties {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Queue mode cache TTL must be positive");
        }
    }
}
