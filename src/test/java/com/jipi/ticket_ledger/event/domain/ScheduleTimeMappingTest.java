package com.jipi.ticket_ledger.event.domain;

import com.jipi.ticket_ledger.support.PostgresTestContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.jipi.ticket_ledger.global.config.DataInitializer;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class ScheduleTimeMappingTest extends PostgresTestContainerSupport {

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private DataInitializer dataInitializer;

    @Test
    @Transactional
    @DisplayName("Schedule: 공연장 로컬 회차 시간은 DB timestamp without time zone 값을 그대로 읽는다")
    void scheduleLocalDateTimeKeepsDatabaseWallClockTime() {
        LocalDateTime startAt = LocalDateTime.of(2026, 6, 20, 10, 0);
        LocalDateTime endAt = LocalDateTime.of(2026, 6, 20, 12, 30);
        Long eventId = jdbcTemplate.queryForObject("""
                        INSERT INTO events(title, description, venue, booking_open_at, created_at)
                        VALUES ('Time Mapping Event', 'description', 'venue', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        RETURNING id
                        """,
                Long.class
        );
        Long scheduleId = jdbcTemplate.queryForObject("""
                        INSERT INTO schedules(event_id, start_at, end_at, created_at)
                        VALUES (?, TIMESTAMP '2026-06-20 10:00:00', TIMESTAMP '2026-06-20 12:30:00', CURRENT_TIMESTAMP)
                        RETURNING id
                        """,
                Long.class,
                eventId
        );

        Schedule found = scheduleRepository.findById(scheduleId).orElseThrow();

        assertEquals(startAt, found.getStartAt());
        assertEquals(endAt, found.getEndAt());
    }
}
