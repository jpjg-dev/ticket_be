package com.jipi.ticket_ledger.event.presentation;

import com.jipi.ticket_ledger.event.application.EventService;
import com.jipi.ticket_ledger.event.presentation.dto.EventDetailResponse;
import com.jipi.ticket_ledger.event.presentation.dto.EventListResponse;
import com.jipi.ticket_ledger.seat.presentation.dto.SeatAvailabilityResponse;
import com.jipi.ticket_ledger.seat.presentation.dto.SeatListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.List;

@Tag(name = "Event API", description = "공연/회차/좌석 조회 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/event")
public class EventController {

    private final EventService eventService;

    @Operation(summary = "공연 목록 조회", description = "공연 목록과 회차 요약 정보를 조회합니다.")
    @GetMapping
    public List<EventListResponse> getEvents() { return eventService.getEvents(); }

    @Operation(summary = "공연 상세 조회", description = "공연 상세와 회차 정보를 조회합니다.")
    @GetMapping("/{eventId}")
    public EventDetailResponse getEvent(@PathVariable Long eventId) {
        return eventService.getEvent(eventId);
    }

    @Operation(summary = "회차 좌석 조회", description = "특정 회차의 좌석 목록을 조회합니다.")
    @GetMapping("/schedules/{scheduleId}/seats")
    public SeatListResponse getSeats(@PathVariable Long scheduleId) {
        return eventService.getSeats(scheduleId);
    }

    @Operation(summary = "회차 매진 여부 일괄 조회", description = "여러 회차의 좌석 상태 요약과 매진 여부를 조회합니다.")
    @GetMapping("/schedules/availability")
    public List<SeatAvailabilityResponse> getScheduleAvailability(@RequestParam Collection<Long> scheduleIds) {
        return eventService.getScheduleAvailability(scheduleIds);
    }
}
