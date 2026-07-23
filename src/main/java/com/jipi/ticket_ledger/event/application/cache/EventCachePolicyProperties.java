package com.jipi.ticket_ledger.event.application.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("cache.event.policy")
public record EventCachePolicyProperties(
        int maxConcurrentDatabaseLoads,
        Duration refreshWaitTimeout,
        Duration refreshRetryInterval,
        Duration refreshLockTtl,
        long retryAfterSeconds,
        double ttlJitterRatio,
        Duration scheduleBoundarySafetyMargin
) {
}
