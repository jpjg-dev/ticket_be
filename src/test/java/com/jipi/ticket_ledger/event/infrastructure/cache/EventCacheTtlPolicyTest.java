package com.jipi.ticket_ledger.event.infrastructure.cache;

import com.jipi.ticket_ledger.event.application.cache.EventCachePolicyProperties;
import com.jipi.ticket_ledger.event.application.model.EventDetailResponse;
import com.jipi.ticket_ledger.event.application.model.ScheduleResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventCacheTtlPolicyTest {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-13T03:00:00Z"), SERVICE_ZONE);

    @Test
    @DisplayName("상세 캐시 TTL은 가장 가까운 회차 시작 직전에 만료된다")
    void expiresBeforeNearestScheduleStarts() {
        EventCacheTtlPolicy ttlPolicy = new EventCacheTtlPolicy(CLOCK, policy(),
                Duration.ofSeconds(60), Duration.ofMinutes(5));
        EventDetailResponse response = new EventDetailResponse(1L, "event", "description", "venue",
                CLOCK.instant(), List.of(new ScheduleResponse(1L,
                LocalDateTime.now(CLOCK).plusSeconds(30), LocalDateTime.now(CLOCK).plusHours(2))));

        assertEquals(Duration.ofSeconds(29), ttlPolicy.getTimeToLive(1L, response));
    }

    private EventCachePolicyProperties policy() {
        return new EventCachePolicyProperties(2, Duration.ofMillis(300), Duration.ofMillis(20),
                Duration.ofSeconds(2), 1, 0.0, Duration.ofSeconds(1));
    }
}
