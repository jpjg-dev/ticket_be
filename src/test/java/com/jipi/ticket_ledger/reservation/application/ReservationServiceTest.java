package com.jipi.ticket_ledger.reservation.application;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupStatus;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationStatus;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.seat.domain.SeatRepository;
import com.jipi.ticket_ledger.seat.domain.SeatStatus;
import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private ReservationGroupRepository reservationGroupRepository;

    @InjectMocks
    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(reservationService, "holdDuration", Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("createReservation: 정상적으로 예약 생성되고 좌석/예약 상태가 변경된다")
    void createReservationSuccess() {
        User user = createUser();
        Seat seat = createSeat();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(seatRepository.findAllByIdInForUpdate(List.of(10L))).thenReturn(List.of(seat));
        when(reservationGroupRepository.save(any(ReservationGroup.class))).thenAnswer(invocation -> {
            ReservationGroup reservationGroup = invocation.getArgument(0);
            ReflectionTestUtils.setField(reservationGroup, "id", 77L);
            return reservationGroup;
        });
        Long reservationGroupId = reservationService.createReservation(1L, List.of(10L));

        assertEquals(77L, reservationGroupId);
        assertEquals(SeatStatus.HELD, seat.getStatus());

        ArgumentCaptor<Iterable<Reservation>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(reservationRepository).saveAll(captor.capture());

        Reservation savedReservation = captor.getValue().iterator().next();
        assertEquals(ReservationStatus.PENDING, savedReservation.getStatus());
        assertNotNull(savedReservation.getReservationGroup());
        assertEquals(ReservationGroupStatus.PENDING, savedReservation.getReservationGroup().getStatus());
        assertNotNull(savedReservation.getExpiresAt());
        assertEquals(savedReservation.getReservedAt().plus(Duration.ofMinutes(5)), savedReservation.getExpiresAt());
        assertEquals(savedReservation.getReservationGroup().getExpiresAt(), savedReservation.getExpiresAt());
        verify(reservationGroupRepository, never()).findByExpiresAtLessThanEqual(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("createReservation: 다중 좌석은 정렬된 id 순서로 비관적 락 조회를 요청한다")
    void createReservationSortsSeatIdsBeforeLockQuery() {
        User user = createUser();
        Seat seat1 = createSeat();
        Seat seat2 = createSeat();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(seatRepository.findAllByIdInForUpdate(List.of(10L, 20L))).thenReturn(List.of(seat1, seat2));
        when(reservationGroupRepository.save(any(ReservationGroup.class))).thenAnswer(invocation -> {
            ReservationGroup reservationGroup = invocation.getArgument(0);
            ReflectionTestUtils.setField(reservationGroup, "id", 77L);
            return reservationGroup;
        });

        reservationService.createReservation(1L, List.of(20L, 10L));

        verify(seatRepository).findAllByIdInForUpdate(List.of(10L, 20L));
        verify(reservationRepository).saveAll(any());
    }

    @Test
    @DisplayName("createReservation: 사용자가 없으면 예외가 발생한다")
    void createReservationUserNotFound() {
        when(userRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> reservationService.createReservation(1L, List.of(10L)));

        verify(seatRepository, never()).findAllByIdInForUpdate(any());
        verify(reservationRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("createReservation: 좌석이 없으면 예외가 발생한다")
    void createReservationSeatNotFound() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(createUser()));
        when(seatRepository.findAllByIdInForUpdate(List.of(10L))).thenReturn(List.of());

        assertThrows(EntityNotFoundException.class,
                () -> reservationService.createReservation(1L, List.of(10L)));

        verify(reservationRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("createReservation: AVAILABLE이 아닌 좌석은 예약할 수 없다")
    void createReservationSeatNotAvailable() {
        User user = createUser();
        Seat seat = createSeat();
        seat.hold();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(seatRepository.findAllByIdInForUpdate(List.of(10L))).thenReturn(List.of(seat));
        when(reservationGroupRepository.save(any(ReservationGroup.class))).thenAnswer(invocation -> {
            ReservationGroup reservationGroup = invocation.getArgument(0);
            ReflectionTestUtils.setField(reservationGroup, "id", 77L);
            return reservationGroup;
        });

        assertThrows(IllegalStateException.class,
                () -> reservationService.createReservation(1L, List.of(10L)));

        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("createReservation: 예매 오픈 전 공연의 좌석은 예약할 수 없다")
    void createReservationBeforeBookingOpen() {
        User user = createUser();
        Seat seat = createSeatWithBookingOpenAt(LocalDateTime.now().plusMinutes(10));

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(seatRepository.findAllByIdInForUpdate(List.of(10L))).thenReturn(List.of(seat));
        lenient().when(reservationGroupRepository.save(any(ReservationGroup.class))).thenAnswer(invocation -> {
            ReservationGroup reservationGroup = invocation.getArgument(0);
            ReflectionTestUtils.setField(reservationGroup, "id", 77L);
            return reservationGroup;
        });

        assertThrows(IllegalStateException.class,
                () -> reservationService.createReservation(1L, List.of(10L)));

        assertEquals(SeatStatus.AVAILABLE, seat.getStatus());
        verify(reservationGroupRepository, never()).save(any());
        verify(reservationRepository, never()).saveAll(any());
    }

    private Seat createSeat() {
        return createSeatWithBookingOpenAt(LocalDateTime.now());
    }

    private Seat createSeatWithBookingOpenAt(LocalDateTime bookingOpenAt) {
        Event event = new Event("공연", "설명", "장소", bookingOpenAt, LocalDateTime.now());
        Schedule schedule = new Schedule(event, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(2), LocalDateTime.now());
        return new Seat(schedule, "A-1", "VIP", 100000, LocalDateTime.now());
    }

    private User createUser() {
        return new User("user@test.com", "password", "테스터", LocalDateTime.now());
    }
}

