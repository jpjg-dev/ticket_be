package com.jipi.ticket_ledger.event.application;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.EventRepository;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.event.domain.ScheduleRepository;
import com.jipi.ticket_ledger.event.presentation.dto.EventResponse;
import com.jipi.ticket_ledger.event.presentation.dto.ScheduleResponse;
import com.jipi.ticket_ledger.global.config.CacheNames;
import com.jipi.ticket_ledger.reservation.application.ReservationExpirationService;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.seat.domain.SeatRepository;
import com.jipi.ticket_ledger.seat.domain.SeatStatus;
import com.jipi.ticket_ledger.seat.presentation.dto.SeatAvailabilityResponse;
import com.jipi.ticket_ledger.seat.presentation.dto.SeatListResponse;
import com.jipi.ticket_ledger.seat.presentation.dto.SeatResponse;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final ScheduleRepository scheduleRepository;
    private final SeatRepository seatRepository;
    private final ReservationExpirationService reservationExpirationService;

    @Transactional(readOnly = true)
    @Cacheable(CacheNames.EVENT_LIST)
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

    @Transactional(readOnly = true)
    @Cacheable(value = CacheNames.EVENT_DETAIL, key = "#eventId")
    public EventResponse getEvent(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new EntityNotFoundException("공연을 찾을 수 없습니다."));

        List<Schedule> schedules = scheduleRepository.findByEventIdOrderByStartAtAsc(eventId);
        Map<Long, List<Seat>> seatsByScheduleId = groupSeatsByScheduleId(
                seatRepository.findByScheduleIdIn(schedules.stream().map(Schedule::getId).toList())
        );

        return toEventResponse(event, schedules, seatsByScheduleId);
    }

    public SeatListResponse getSeats(Long scheduleId) {
        if (!scheduleRepository.existsById(scheduleId)) {
            throw new EntityNotFoundException("회차를 찾을 수 없습니다.");
        }

        reservationExpirationService.expireByScheduleId(scheduleId);

        SeatAvailabilityResponse availability = getScheduleAvailability(List.of(scheduleId)).stream()
                .findFirst()
                .orElse(new SeatAvailabilityResponse(scheduleId, false, 0, 0, 0));

        if (availability.soldOut()) {
            return new SeatListResponse(scheduleId, true, List.of());
        }

        List<SeatResponse> seats = seatRepository.findByScheduleId(scheduleId).stream()
                .map(seat -> new SeatResponse(
                        seat.getId(),
                        scheduleId,
                        seat.getSeatNumber(),
                        seat.getGrade(),
                        seat.getPrice(),
                        seat.getStatus().name()
                ))
                .toList();

        return new SeatListResponse(scheduleId, false, seats);
    }

    @Transactional(readOnly = true)
    public List<SeatAvailabilityResponse> getScheduleAvailability(Collection<Long> scheduleIds) {
        Set<Long> distinctScheduleIds = scheduleIds.stream()
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (distinctScheduleIds.isEmpty()) {
            return List.of();
        }

        if (distinctScheduleIds.size() > 100) {
            throw new IllegalArgumentException("회차 매진 여부는 한 번에 최대 100개까지 조회할 수 있습니다.");
        }

        Map<Long, EnumMap<SeatStatus, Long>> countsByScheduleId = seatRepository.countStatusesByScheduleIds(distinctScheduleIds)
                .stream()
                .collect(Collectors.groupingBy(
                        SeatRepository.SeatStatusCount::getScheduleId,
                        Collectors.toMap(
                                SeatRepository.SeatStatusCount::getStatus,
                                SeatRepository.SeatStatusCount::getCount,
                                Long::sum,
                                () -> new EnumMap<>(SeatStatus.class)
                        )
                ));

        return distinctScheduleIds.stream()
                .map(scheduleId -> toAvailabilityResponse(scheduleId, countsByScheduleId.get(scheduleId)))
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

    private SeatAvailabilityResponse toAvailabilityResponse(Long scheduleId, Map<SeatStatus, Long> counts) {
        long available = getCount(counts, SeatStatus.AVAILABLE);
        long held = getCount(counts, SeatStatus.HELD);
        long booked = getCount(counts, SeatStatus.BOOKED);
        boolean soldOut = available == 0 && held == 0 && booked > 0;

        return new SeatAvailabilityResponse(scheduleId, soldOut, available, held, booked);
    }

    private long getCount(Map<SeatStatus, Long> counts, SeatStatus status) {
        if (counts == null) {
            return 0;
        }

        return counts.getOrDefault(status, 0L);
    }
}
