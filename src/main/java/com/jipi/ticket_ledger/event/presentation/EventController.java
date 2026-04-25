package com.jipi.ticket_ledger.event.presentation;

import com.jipi.ticket_ledger.event.application.EventService;
import com.jipi.ticket_ledger.event.presentation.dto.EventResponse;
import com.jipi.ticket_ledger.seat.presentation.dto.SeatResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Event API", description = "공연/회차/좌석 조회 API")
@RestController
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @Operation(summary = "공연 목록 조회", description = "공연 목록과 회차 요약 정보를 조회합니다.")
    @GetMapping("/events")
    public List<EventResponse> getEvents() {
        return eventService.getEvents();
    }

    @Operation(summary = "공연 상세 조회", description = "공연 상세와 회차 정보를 조회합니다.")
    @GetMapping("/events/{eventId}")
    public EventResponse getEvent(@PathVariable Long eventId) {
        return eventService.getEvent(eventId);
    }

    @Operation(summary = "회차 좌석 조회", description = "특정 회차의 좌석 목록을 조회합니다.")
    @GetMapping("/schedules/{scheduleId}/seats")
    public List<SeatResponse> getSeats(@PathVariable Long scheduleId) {
        return eventService.getSeats(scheduleId);
    }
}
