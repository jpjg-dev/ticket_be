package com.jipi.ticket_ledger.event.presentation.dto;

import java.time.LocalDateTime;
import java.util.List;

public record EventListResponse(
        Long id,
        String title,
        String description,
        String venue,
        LocalDateTime bookingOpenAt,
        List<ScheduleResponse> schedules
) {
}
