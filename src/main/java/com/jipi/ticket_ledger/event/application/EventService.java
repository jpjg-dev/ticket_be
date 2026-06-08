package com.jipi.ticket_ledger.event.application;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.EventRepository;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.event.domain.ScheduleRepository;
import com.jipi.ticket_ledger.event.presentation.dto.EventResponse;
import com.jipi.ticket_ledger.event.presentation.dto.ScheduleResponse;
import com.jipi.ticket_ledger.reservation.application.ReservationExpirationService;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.seat.domain.SeatRepository;
import com.jipi.ticket_ledger.seat.presentation.dto.SeatResponse;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventService {

    private final EventRepository eventRepository;
    private final ScheduleRepository scheduleRepository;
    private final SeatRepository seatRepository;
    private final ReservationExpirationService reservationExpirationService;

    public List<EventResponse> getEvents() {
        List<Event> events = eventRepository.findAllByOrderByBookingOpenAtAsc();
        if (events.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, List<Schedule>> schedulesByEventId = groupSchedulesByEventId(
                scheduleRepository.findByEventIdInOrderByStartAtAsc(
                        events.stream().map(Event::getId).toList()
                )
        );

        Map<Long, List<Seat>> seatsByScheduleId = groupSeatsByScheduleId(
                seatRepository.findByScheduleIdIn(
                        schedulesByEventId.values().stream()
                                .flatMap(List::stream)
                                .map(Schedule::getId)
                                .toList()
                )
        );

        return events.stream()
                .map(event -> toEventResponse(event, schedulesByEventId.getOrDefault(event.getId(), List.of()), seatsByScheduleId))
                .toList();
    }

    public EventResponse getEvent(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new EntityNotFoundException("공연을 찾을 수 없습니다."));

        List<Schedule> schedules = scheduleRepository.findByEventIdOrderByStartAtAsc(eventId);
        Map<Long, List<Seat>> seatsByScheduleId = groupSeatsByScheduleId(
                seatRepository.findByScheduleIdIn(schedules.stream().map(Schedule::getId).toList())
        );

        return toEventResponse(event, schedules, seatsByScheduleId);
    }

    @Transactional
    public List<SeatResponse> getSeats(Long scheduleId) {
        if (!scheduleRepository.existsById(scheduleId)) {
            throw new EntityNotFoundException("회차를 찾을 수 없습니다.");
        }

        reservationExpirationService.expireByScheduleId(scheduleId);

        return seatRepository.findByScheduleId(scheduleId).stream()
                .map(seat -> new SeatResponse(
                        seat.getId(),
                        seat.getSchedule().getId(),
                        seat.getSeatNumber(),
                        seat.getGrade(),
                        seat.getPrice(),
                        seat.getStatus().name()
                ))
                .toList();
    }

    private Map<Long, List<Schedule>> groupSchedulesByEventId(List<Schedule> schedules) {
        return schedules.stream()
                .collect(Collectors.groupingBy(schedule -> schedule.getEvent().getId()));
    }

    private Map<Long, List<Seat>> groupSeatsByScheduleId(List<Seat> seats) {
        return seats.stream()
                .collect(Collectors.groupingBy(seat -> seat.getSchedule().getId()));
    }

    private EventResponse toEventResponse(Event event, List<Schedule> schedules, Map<Long, List<Seat>> seatsByScheduleId) {
        List<ScheduleResponse> scheduleResponses = schedules.stream()
                .map(schedule -> new ScheduleResponse(schedule.getId(), schedule.getStartAt(), schedule.getEndAt()))
                .toList();

        List<Seat> seats = schedules.stream()
                .map(Schedule::getId)
                .map(scheduleId -> seatsByScheduleId.getOrDefault(scheduleId, List.of()))
                .flatMap(List::stream)
                .toList();

        LocalDateTime runStartAt = schedules.stream()
                .map(Schedule::getStartAt)
                .min(LocalDateTime::compareTo)
                .orElse(null);
        LocalDateTime runEndAt = schedules.stream()
                .map(Schedule::getEndAt)
                .max(LocalDateTime::compareTo)
                .orElse(null);

        Integer minPrice = seats.stream()
                .map(Seat::getPrice)
                .min(Integer::compareTo)
                .orElse(null);
        Integer maxPrice = seats.stream()
                .map(Seat::getPrice)
                .max(Integer::compareTo)
                .orElse(null);

        return new EventResponse(
                event.getId(),
                event.getTitle(),
                event.getDescription(),
                event.getVenue(),
                event.getBookingOpenAt(),
                runStartAt,
                runEndAt,
                minPrice,
                maxPrice,
                scheduleResponses
        );
    }
}
