package com.jipi.ticket_ledger.event.application.model;

import java.time.Instant;
import java.util.List;

public record EventDetailResponse(
        Long id,
        String title,
        String description,
        String venue,
        Instant bookingOpenAt,
        List<ScheduleResponse> schedules
) {
}
