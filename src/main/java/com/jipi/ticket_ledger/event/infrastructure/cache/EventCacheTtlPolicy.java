package com.jipi.ticket_ledger.event.infrastructure.cache;

import com.jipi.ticket_ledger.event.application.cache.EventCachePolicyProperties;
import com.jipi.ticket_ledger.event.application.model.EventDetailResponse;
import com.jipi.ticket_ledger.event.application.model.EventListCacheResponse;
import com.jipi.ticket_ledger.event.application.model.ScheduleResponse;
import org.springframework.data.redis.cache.RedisCacheWriter;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class EventCacheTtlPolicy implements RedisCacheWriter.TtlFunction {

    private static final Duration MINIMUM_TTL = Duration.ofMillis(1);

    private final Clock clock;
    private final EventCachePolicyProperties policy;
    private final Duration eventListTtl;
    private final Duration eventDetailTtl;

    public EventCacheTtlPolicy(Clock clock, EventCachePolicyProperties policy,
                               Duration eventListTtl, Duration eventDetailTtl) {
        this.clock = clock;
        this.policy = policy;
        this.eventListTtl = eventListTtl;
        this.eventDetailTtl = eventDetailTtl;
    }

    @Override
    public Duration getTimeToLive(Object key, Object value) {
        Duration baseTtl = value instanceof EventListCacheResponse ? eventListTtl : eventDetailTtl;
        Duration jitteredTtl = applyJitter(baseTtl);
        return nearestScheduleStart(value)
                .map(startAt -> Duration.between(LocalDateTime.now(clock), startAt)
                        .minus(policy.scheduleBoundarySafetyMargin()))
                .map(boundaryTtl -> boundaryTtl.compareTo(jitteredTtl) < 0 ? boundaryTtl : jitteredTtl)
                .filter(ttl -> !ttl.isNegative() && !ttl.isZero())
                .orElseGet(() -> nearestScheduleStart(value).isEmpty() ? jitteredTtl : MINIMUM_TTL);
    }

    private Duration applyJitter(Duration ttl) {
        if (policy.ttlJitterRatio() == 0.0) {
            return ttl;
        }
        double ratio = ThreadLocalRandom.current()
                .nextDouble(-policy.ttlJitterRatio(), policy.ttlJitterRatio());
        return Duration.ofMillis(Math.max(1L, Math.round(ttl.toMillis() * (1.0 + ratio))));
    }

    private java.util.Optional<LocalDateTime> nearestScheduleStart(Object value) {
        List<ScheduleResponse> schedules = switch (value) {
            case EventListCacheResponse response -> response.events().stream()
                    .flatMap(event -> event.schedules().stream())
                    .toList();
            case EventDetailResponse response -> response.schedules();
            default -> List.of();
        };
        return schedules.stream().map(ScheduleResponse::startAt).min(LocalDateTime::compareTo);
    }
}
