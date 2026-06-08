package com.jipi.ticket_ledger.event.application;

import com.jipi.ticket_ledger.event.domain.ScheduleRepository;
import com.jipi.ticket_ledger.event.domain.EventRepository;
import com.jipi.ticket_ledger.global.config.CacheNames;
import com.jipi.ticket_ledger.global.config.DataInitializer;
import com.jipi.ticket_ledger.reservation.application.ReservationExpirationService;
import com.jipi.ticket_ledger.seat.domain.SeatRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private ReservationExpirationService reservationExpirationService;

    @InjectMocks
    private EventService eventService;

    @Test
    @DisplayName("getSeats: 해당 회차의 만료 예약을 정리한 뒤 좌석을 조회한다")
    void getSeatsExpiresRequestedScheduleBeforeReadingSeats() {
        when(scheduleRepository.existsById(10L)).thenReturn(true);
        when(seatRepository.findByScheduleId(10L)).thenReturn(List.of());

        eventService.getSeats(10L);

        var inOrder = inOrder(reservationExpirationService, seatRepository);
        inOrder.verify(reservationExpirationService).expireByScheduleId(10L);
        inOrder.verify(seatRepository).findByScheduleId(10L);
    }

    @Nested
    @SpringBootTest(properties = "spring.cache.type=caffeine")
    class EventCacheTest {

        @Autowired
        private EventService cacheEventService;

        @Autowired
        private CacheManager cacheManager;

        @MockitoBean
        private EventRepository eventRepository;

        @MockitoBean
        private ScheduleRepository scheduleRepository;

        @MockitoBean
        private SeatRepository seatRepository;

        @MockitoBean
        private ReservationExpirationService reservationExpirationService;

        @MockitoBean
        private DataInitializer dataInitializer;

        @BeforeEach
        void clearCaches() {
            cacheManager.getCache(CacheNames.EVENT_LIST).clear();
            cacheManager.getCache(CacheNames.EVENT_DETAIL).clear();
        }

        @Test
        @DisplayName("getEvents: 공연 목록 조회는 캐시되어 DB 조회를 반복하지 않는다")
        void getEventsUsesCache() {
            when(eventRepository.findAllByOrderByBookingOpenAtAsc()).thenReturn(List.of());

            cacheEventService.getEvents();
            cacheEventService.getEvents();

            verify(eventRepository, times(1)).findAllByOrderByBookingOpenAtAsc();
            verify(scheduleRepository, never()).findByEventIdInOrderByStartAtAsc(anyCollection());
            verify(seatRepository, never()).findByScheduleIdIn(anyCollection());
        }

        @Test
        @DisplayName("getEvent: 같은 공연 상세 조회는 캐시되어 DB 조회를 반복하지 않는다")
        void getEventUsesCacheForSameEventId() {
            var now = LocalDateTime.of(2026, 6, 8, 12, 0);
            var event = new com.jipi.ticket_ledger.event.domain.Event(
                    "Cache Event",
                    "description",
                    "venue",
                    now.minusDays(1),
                    now
            );

            when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
            when(scheduleRepository.findByEventIdOrderByStartAtAsc(1L)).thenReturn(List.of());
            when(seatRepository.findByScheduleIdIn(anyCollection())).thenReturn(List.of());

            cacheEventService.getEvent(1L);
            cacheEventService.getEvent(1L);

            verify(eventRepository, times(1)).findById(1L);
            verify(scheduleRepository, times(1)).findByEventIdOrderByStartAtAsc(1L);
            verify(seatRepository, times(1)).findByScheduleIdIn(anyCollection());
        }
    }
}
