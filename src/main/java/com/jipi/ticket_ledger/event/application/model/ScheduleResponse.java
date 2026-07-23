package com.jipi.ticket_ledger.event.application.model;

import java.time.LocalDateTime;

public record ScheduleResponse(
        Long id,
        LocalDateTime startAt,
        LocalDateTime endAt
) {
}
