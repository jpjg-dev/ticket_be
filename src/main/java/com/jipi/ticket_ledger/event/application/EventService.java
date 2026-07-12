package com.jipi.ticket_ledger.event.application;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.EventRepository;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.event.domain.ScheduleRepository;
import com.jipi.ticket_ledger.event.presentation.dto.EventDetailResponse;
import com.jipi.ticket_ledger.event.presentation.dto.EventListCacheResponse;
import com.jipi.ticket_ledger.event.presentation.dto.EventListResponse;
import com.jipi.ticket_ledger.event.presentation.dto.ScheduleResponse;
import com.jipi.ticket_ledger.global.config.CacheNames;
import com.jipi.ticket_ledger.reservation.application.ReservationExpirationService;
import com.jipi.ticket_ledger.seat.domain.SeatRepository;
import com.jipi.ticket_ledger.seat.domain.SeatStatus;
import com.jipi.ticket_ledger.seat.presentation.dto.SeatAvailabilityResponse;
import com.jipi.ticket_ledger.seat.presentation.dto.SeatListResponse;
import com.jipi.ticket_ledger.seat.presentation.dto.SeatResponse;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
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
@Slf4j
public class EventService {

    private final EventRepository eventRepository;
    private final ScheduleRepository scheduleRepository;
    private final SeatRepository seatRepository;
    private final ReservationExpirationService reservationExpirationService;

    @Value("${app.time.service-zone:Asia/Seoul}")
    private String serviceZoneId = "Asia/Seoul";

    @Transactional(readOnly = true)
    @Cacheable(value = CacheNames.EVENT_LIST, key = "'all'")
    public EventListCacheResponse getEvents() {
        List<Event> events = eventRepository.findAllByOrderByBookingOpenAtAsc();
        if (events.isEmpty()) {
            return new EventListCacheResponse(Collections.emptyList());
        }

        LocalDateTime now = LocalDateTime.now(ZoneId.of(serviceZoneId));
        Map<Long, List<Schedule>> schedulesByEventId = groupSchedulesByEventId(
                scheduleRepository.findByEventIdInAndStartAtAfterOrderByStartAtAsc(
                        events.stream().map(Event::getId).toList(),
                        now
                )
        );

        List<EventListResponse> eventResponses = events.stream()
                .map(event -> toEventListResponse(event, schedulesByEventId.getOrDefault(event.getId(), List.of())))
                .filter(event -> !event.schedules().isEmpty())
                .toList();
        return new EventListCacheResponse(eventResponses);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = CacheNames.EVENT_DETAIL, key = "#eventId")
    public EventDetailResponse getEvent(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new EntityNotFoundException("공연을 찾을 수 없습니다."));

        LocalDateTime now = LocalDateTime.now(ZoneId.of(serviceZoneId));
        List<Schedule> schedules = scheduleRepository.findByEventIdAndStartAtAfterOrderByStartAtAsc(eventId, now);
        return toEventDetailResponse(event, schedules);
    }

    public SeatListResponse getSeats(Long scheduleId) {
        long startNanos = System.nanoTime();
        long afterExpirationNanos;
        long afterAvailabilityNanos;
        long afterRepositoryNanos;
        long afterMappingNanos;
        long afterSoldOutNanos;

//        if (!scheduleRepository.existsById(scheduleId)) {
//            throw new EntityNotFoundException("회차를 찾을 수 없습니다.");
//        }
        reservationExpirationService.expireByScheduleId(scheduleId);
        afterExpirationNanos = System.nanoTime();

        SeatAvailabilityResponse availability = getScheduleAvailability(List.of(scheduleId)).stream()
                .findFirst()
                .orElse(new SeatAvailabilityResponse(scheduleId, false, 0, 0, 0));
        afterAvailabilityNanos = System.nanoTime();

        if (availability.soldOut()) {
            log.debug("event=SEAT_LOOKUP_PROFILE scheduleId={} expirationMs={} availabilityMs={} repositoryMs={} mappingMs={} soldOutMs={} serviceTotalMs={} seatCount={} soldOut={}",
                    scheduleId,
                    elapsedMillis(startNanos, afterExpirationNanos),
                    elapsedMillis(afterExpirationNanos, afterAvailabilityNanos),
                    0.0,
                    0.0,
                    0.0,
                    elapsedMillis(startNanos, afterAvailabilityNanos),
                    0,
                    true);
            return new SeatListResponse(scheduleId, true, List.of());
        }

        List<SeatRepository.SeatSummary> seatSummaries = seatRepository.findAvailableSeatSummariesByScheduleId(scheduleId);
        afterRepositoryNanos = System.nanoTime();

        List<SeatResponse> seats = seatSummaries.stream()
                .map(summary -> new SeatResponse(
                        summary.getId(),
                        summary.getSeatNumber(),
                        summary.getGrade(),
                        summary.getPrice(),
                        summary.getStatus().name()
                ))
                .toList();
        afterMappingNanos = System.nanoTime();

        boolean soldOut = availability.soldOut();
        afterSoldOutNanos = System.nanoTime();

        log.debug("event=SEAT_LOOKUP_PROFILE scheduleId={} expirationMs={} availabilityMs={} repositoryMs={} mappingMs={} soldOutMs={} serviceTotalMs={} seatCount={} soldOut={}",
                scheduleId,
                elapsedMillis(startNanos, afterExpirationNanos),
                elapsedMillis(afterExpirationNanos, afterAvailabilityNanos),
                elapsedMillis(afterAvailabilityNanos, afterRepositoryNanos),
                elapsedMillis(afterRepositoryNanos, afterMappingNanos),
                elapsedMillis(afterMappingNanos, afterSoldOutNanos),
                elapsedMillis(startNanos, afterSoldOutNanos),
                seats.size(),
                soldOut);

        return new SeatListResponse(scheduleId, soldOut, seats);
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

    private EventListResponse toEventListResponse(Event event, List<Schedule> schedules) {
        List<ScheduleResponse> scheduleResponses = schedules.stream()
                .map(schedule -> new ScheduleResponse(schedule.getId(), schedule.getStartAt(), schedule.getEndAt()))
                .toList();

        return new EventListResponse(
                event.getId(),
                event.getTitle(),
                event.getDescription(),
                event.getVenue(),
                event.getBookingOpenAt(),
                scheduleResponses
        );
    }

    private EventDetailResponse toEventDetailResponse(Event event, List<Schedule> schedules) {
        List<ScheduleResponse> scheduleResponses = schedules.stream()
                .map(schedule -> new ScheduleResponse(schedule.getId(), schedule.getStartAt(), schedule.getEndAt()))
                .toList();

        return new EventDetailResponse(
                event.getId(),
                event.getTitle(),
                event.getDescription(),
                event.getVenue(),
                event.getBookingOpenAt(),
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

    private double elapsedMillis(long startNanos, long endNanos) {
        return (endNanos - startNanos) / 1_000_000.0;
    }
}
