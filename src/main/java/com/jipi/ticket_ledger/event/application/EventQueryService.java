package com.jipi.ticket_ledger.event.application;

import com.jipi.ticket_ledger.event.application.model.EventDetailResponse;
import com.jipi.ticket_ledger.event.application.model.EventListCacheResponse;
import com.jipi.ticket_ledger.event.application.model.EventListResponse;
import com.jipi.ticket_ledger.event.application.model.ScheduleResponse;
import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.EventRepository;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.event.domain.ScheduleRepository;
import com.jipi.ticket_ledger.global.config.CacheNames;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EventQueryService {

    private final EventRepository eventRepository;
    private final ScheduleRepository scheduleRepository;

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
        Map<Long, List<Schedule>> schedulesByEventId = scheduleRepository
                .findByEventIdInAndStartAtAfterOrderByStartAtAsc(events.stream().map(Event::getId).toList(), now)
                .stream()
                .collect(Collectors.groupingBy(schedule -> schedule.getEvent().getId()));

        List<EventListResponse> responses = events.stream()
                .map(event -> toEventListResponse(event, schedulesByEventId.getOrDefault(event.getId(), List.of())))
                .filter(event -> !event.schedules().isEmpty())
                .toList();
        return new EventListCacheResponse(responses);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = CacheNames.EVENT_DETAIL, key = "#eventId")
    public EventDetailResponse getEvent(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new EntityNotFoundException("공연을 찾을 수 없습니다."));
        LocalDateTime now = LocalDateTime.now(ZoneId.of(serviceZoneId));
        return toEventDetailResponse(
                event,
                scheduleRepository.findByEventIdAndStartAtAfterOrderByStartAtAsc(eventId, now)
        );
    }

    private EventListResponse toEventListResponse(Event event, List<Schedule> schedules) {
        return new EventListResponse(event.getId(), event.getTitle(), event.getDescription(), event.getVenue(),
                event.getBookingOpenAt(), toScheduleResponses(schedules));
    }

    private EventDetailResponse toEventDetailResponse(Event event, List<Schedule> schedules) {
        return new EventDetailResponse(event.getId(), event.getTitle(), event.getDescription(), event.getVenue(),
                event.getBookingOpenAt(), toScheduleResponses(schedules));
    }

    private List<ScheduleResponse> toScheduleResponses(List<Schedule> schedules) {
        return schedules.stream()
                .map(schedule -> new ScheduleResponse(schedule.getId(), schedule.getStartAt(), schedule.getEndAt()))
                .toList();
    }
}
