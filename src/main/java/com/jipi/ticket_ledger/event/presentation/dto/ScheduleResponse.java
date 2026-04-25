package com.jipi.ticket_ledger.event.presentation.dto;

import java.time.LocalDateTime;

public record ScheduleResponse(
        Long id,
        LocalDateTime startAt,
        LocalDateTime endAt
) {
}
