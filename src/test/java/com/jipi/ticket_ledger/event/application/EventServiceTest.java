package com.jipi.ticket_ledger.event.application;

import com.jipi.ticket_ledger.event.domain.ScheduleRepository;
import com.jipi.ticket_ledger.event.domain.EventRepository;
import com.jipi.ticket_ledger.global.config.CacheNames;
import com.jipi.ticket_ledger.global.config.DataInitializer;
import com.jipi.ticket_ledger.reservation.application.ReservationExpirationService;
import com.jipi.ticket_ledger.seat.domain.SeatRepository;
import com.jipi.ticket_ledger.seat.domain.SeatStatus;
import com.jipi.ticket_ledger.support.PostgresTestContainerSupport;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
    @DisplayName("getEvents: 메인 목록 조회는 좌석 전체를 조회하지 않고 회차 정보만 조립한다")
    void getEventsDoesNotReadSeatsForMainCatalog() {
        var now = LocalDateTime.of(2026, 6, 10, 12, 0);
        var event = new com.jipi.ticket_ledger.event.domain.Event(
                "Main Event",
                "description",
                "venue",
                now.minusDays(1),
                now
        );
        ReflectionTestUtils.setField(event, "id", 1L);

        var schedule = new com.jipi.ticket_ledger.event.domain.Schedule(
                event,
                now.plusDays(1),
                now.plusDays(1).plusHours(2),
                now
        );
        ReflectionTestUtils.setField(schedule, "id", 10L);

        when(eventRepository.findAllByOrderByBookingOpenAtAsc()).thenReturn(List.of(event));
        when(scheduleRepository.findByEventIdInAndStartAtAfterOrderByStartAtAsc(anyCollection(), any(LocalDateTime.class)))
                .thenReturn(List.of(schedule));

        var responses = eventService.getEvents();

        assertEquals(1, responses.size());
        assertEquals(1L, responses.get(0).id());
        assertEquals(1, responses.get(0).schedules().size());
        assertEquals(10L, responses.get(0).schedules().get(0).id());    }

    @Test
    @DisplayName("getEvents: 예매 가능한 미래 회차가 없는 공연은 메인 목록에서 제외한다")
    void getEventsExcludesEventsWithoutBookableSchedules() {
        var now = LocalDateTime.of(2026, 6, 10, 12, 0);
        var event = new com.jipi.ticket_ledger.event.domain.Event(
                "Ended Event",
                "description",
                "venue",
                now.minusDays(10),
                now
        );
        ReflectionTestUtils.setField(event, "id", 1L);

        when(eventRepository.findAllByOrderByBookingOpenAtAsc()).thenReturn(List.of(event));
        when(scheduleRepository.findByEventIdInAndStartAtAfterOrderByStartAtAsc(anyCollection(), any(LocalDateTime.class)))
                .thenReturn(List.of());

        var responses = eventService.getEvents();

        assertTrue(responses.isEmpty());    }

    @Test
    @DisplayName("getEvent: 상세 조회는 좌석 전체를 조회하지 않고 상세 기본 데이터와 회차만 조립한다")
    void getEventDoesNotReadSeatsForEventDetail() {
        var now = LocalDateTime.of(2026, 6, 10, 12, 0);
        var event = new com.jipi.ticket_ledger.event.domain.Event(
                "Detail Event",
                "description",
                "venue",
                now.minusDays(1),
                now
        );
        ReflectionTestUtils.setField(event, "id", 1L);

        var schedule = new com.jipi.ticket_ledger.event.domain.Schedule(
                event,
                now.plusDays(1),
                now.plusDays(1).plusHours(2),
                now
        );
        ReflectionTestUtils.setField(schedule, "id", 10L);

        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(scheduleRepository.findByEventIdAndStartAtAfterOrderByStartAtAsc(any(Long.class), any(LocalDateTime.class)))
                .thenReturn(List.of(schedule));

        var response = eventService.getEvent(1L);

        assertEquals(1L, response.id());
        assertEquals(1, response.schedules().size());
        assertEquals(10L, response.schedules().get(0).id());    }

    @Test
    @DisplayName("getSeats: 해당 회차의 만료 예약을 정리한 뒤 좌석을 조회한다")
    void getSeatsExpiresRequestedScheduleBeforeReadingSeats() {
        when(seatRepository.countStatusesByScheduleIds(anyCollection())).thenReturn(List.of());
        when(seatRepository.findSeatSummariesByScheduleId(10L)).thenReturn(List.of());

        var response = eventService.getSeats(10L);

        assertFalse(response.soldOut());
        assertEquals(List.of(), response.seats());

        var inOrder = inOrder(reservationExpirationService, seatRepository);
        inOrder.verify(reservationExpirationService).expireByScheduleId(10L);
        inOrder.verify(seatRepository).countStatusesByScheduleIds(anyCollection());
        inOrder.verify(seatRepository).findSeatSummariesByScheduleId(10L);    }

    @Test
    @DisplayName("getSeats: AVAILABLE과 HELD가 없고 BOOKED만 있으면 매진으로 보고 좌석 목록을 조회하지 않는다")
    void getSeatsReturnsSoldOutWithoutReadingSeatListWhenAllSeatsAreBooked() {
        when(seatRepository.countStatusesByScheduleIds(anyCollection())).thenReturn(List.of(
                new StatusCount(10L, SeatStatus.BOOKED, 1000)
        ));

        var response = eventService.getSeats(10L);

        assertTrue(response.soldOut());
        assertEquals(10L, response.scheduleId());
        assertEquals(List.of(), response.seats());
        verify(seatRepository).countStatusesByScheduleIds(anyCollection());
        verify(seatRepository, never()).findSeatSummariesByScheduleId(10L);    }

    @Test
    @DisplayName("getSeats: HELD 좌석이 남아 있으면 만료 복구 가능성이 있으므로 매진으로 보지 않고 좌석 목록을 조회한다")
    void getSeatsDoesNotTreatHeldOnlyScheduleAsSoldOut() {
        when(seatRepository.countStatusesByScheduleIds(anyCollection())).thenReturn(List.of(
                new StatusCount(10L, SeatStatus.HELD, 2),
                new StatusCount(10L, SeatStatus.BOOKED, 998)
        ));
        when(seatRepository.findSeatSummariesByScheduleId(10L)).thenReturn(List.of());

        var response = eventService.getSeats(10L);

        assertFalse(response.soldOut());
        verify(seatRepository).countStatusesByScheduleIds(anyCollection());
        verify(seatRepository).findSeatSummariesByScheduleId(10L);    }

    @Test
    @DisplayName("getSeats: 좌석 응답은 엔티티 전체 조회 없이 projection 필드만 매핑한다")
    void getSeatsMapsSeatSummaryProjection() {
        when(seatRepository.countStatusesByScheduleIds(anyCollection())).thenReturn(List.of(
                new StatusCount(10L, SeatStatus.AVAILABLE, 2)
        ));
        when(seatRepository.findSeatSummariesByScheduleId(10L)).thenReturn(List.of(
                new SeatSummary(1L, "A-1", "R", 1000, SeatStatus.AVAILABLE),
                new SeatSummary(2L, "A-2", "R", 1000, SeatStatus.HELD)
        ));

        var response = eventService.getSeats(10L);

        assertFalse(response.soldOut());
        assertEquals(2, response.seats().size());
        assertEquals(1L, response.seats().get(0).id());
        assertEquals("A-1", response.seats().get(0).seatNumber());
        assertEquals("R", response.seats().get(0).grade());
        assertEquals(1000, response.seats().get(0).price());
        assertEquals("AVAILABLE", response.seats().get(0).status());
        verify(seatRepository).countStatusesByScheduleIds(anyCollection());
        verify(seatRepository).findSeatSummariesByScheduleId(10L);    }

    @Test
    @DisplayName("getScheduleAvailability: 회차별 상태 집계로 매진 여부를 계산한다")
    void getScheduleAvailabilityGroupsStatusCountsBySchedule() {
        when(seatRepository.countStatusesByScheduleIds(anyCollection())).thenReturn(List.of(
                new StatusCount(10L, SeatStatus.BOOKED, 1000),
                new StatusCount(11L, SeatStatus.AVAILABLE, 3),
                new StatusCount(11L, SeatStatus.HELD, 1),
                new StatusCount(11L, SeatStatus.BOOKED, 996)
        ));

        var responses = eventService.getScheduleAvailability(List.of(10L, 11L));

        assertEquals(2, responses.size());
        assertTrue(responses.get(0).soldOut());
        assertEquals(0, responses.get(0).available());
        assertEquals(0, responses.get(0).held());
        assertEquals(1000, responses.get(0).booked());
        assertFalse(responses.get(1).soldOut());
        assertEquals(3, responses.get(1).available());
        assertEquals(1, responses.get(1).held());
        assertEquals(996, responses.get(1).booked());
    }

    @Test
    @DisplayName("getSeats: 좌석 조회 메서드는 만료 처리 write transaction을 감싸지 않는다")
    void getSeatsDoesNotWrapExpirationInEventServiceTransaction() throws Exception {
        assertNull(EventService.class.getAnnotation(org.springframework.transaction.annotation.Transactional.class));

        Method getSeats = EventService.class.getMethod("getSeats", Long.class);
        assertNull(getSeats.getAnnotation(org.springframework.transaction.annotation.Transactional.class));

        Method getEvents = EventService.class.getMethod("getEvents");
        Method getEvent = EventService.class.getMethod("getEvent", Long.class);

        assertTrue(getEvents.getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly());
        assertTrue(getEvent.getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly());
    }

    @Nested
    @SpringBootTest(properties = {
            "spring.cache.type=caffeine",
            "cache.event.list.ttl=60s",
            "cache.event.list.max-size=1",
            "cache.event.detail.ttl=5m",
            "cache.event.detail.max-size=100"
    })
    class EventCacheTest extends PostgresTestContainerSupport {

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
            verify(scheduleRepository, never()).findByEventIdInAndStartAtAfterOrderByStartAtAsc(anyCollection(), any(LocalDateTime.class));        }

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
            when(scheduleRepository.findByEventIdAndStartAtAfterOrderByStartAtAsc(any(Long.class), any(LocalDateTime.class)))
                    .thenReturn(List.of());

            cacheEventService.getEvent(1L);
            cacheEventService.getEvent(1L);

            verify(eventRepository, times(1)).findById(1L);
            verify(scheduleRepository, times(1)).findByEventIdAndStartAtAfterOrderByStartAtAsc(any(Long.class), any(LocalDateTime.class));        }
    }

    private record StatusCount(
            Long scheduleId,
            SeatStatus status,
            long count
    ) implements SeatRepository.SeatStatusCount {

        @Override
        public Long getScheduleId() {
            return scheduleId;
        }

        @Override
        public SeatStatus getStatus() {
            return status;
        }

        @Override
        public long getCount() {
            return count;
        }
    }

    private record SeatSummary(
            Long id,
            String seatNumber,
            String grade,
            Integer price,
            SeatStatus status
    ) implements SeatRepository.SeatSummary {

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public String getSeatNumber() {
            return seatNumber;
        }

        @Override
        public String getGrade() {
            return grade;
        }

        @Override
        public Integer getPrice() {
            return price;
        }

        @Override
        public SeatStatus getStatus() {
            return status;
        }
    }
}
