package com.jipi.ticket_ledger.event.presentation.dto;

import java.time.LocalDateTime;
import java.util.List;

public record EventResponse(
        Long id,
        String title,
        String description,
        String venue,
        LocalDateTime bookingOpenAt,
        LocalDateTime runStartAt,
        LocalDateTime runEndAt,
        Integer minPrice,
        Integer maxPrice,
        List<ScheduleResponse> schedules
) {
}
