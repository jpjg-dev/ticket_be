package com.jipi.ticket_ledger.reservation.application;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.event.domain.ScheduleRepository;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationStatus;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.seat.domain.SeatRepository;
import com.jipi.ticket_ledger.seat.domain.SeatStatus;
import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
    private ScheduleRepository scheduleRepository;

    @Mock
    private SeatRepository seatRepository;

    @InjectMocks
    private ReservationService reservationService;

    @Test
    @DisplayName("createReservation: 정상적으로 예약 생성되고 좌석/예약 상태가 변경된다")
    void createReservationSuccess() {
        User user = createUser();
        Seat seat = createSeat();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(seatRepository.findById(10L)).thenReturn(Optional.of(seat));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            ReflectionTestUtils.setField(reservation, "id", 99L);
            return reservation;
        });

        Long reservationId = reservationService.createReservation(new CreateReservationCommand(1L, 10L));

        assertEquals(99L, reservationId);
        assertEquals(SeatStatus.HELD, seat.getStatus());

        ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).save(captor.capture());

        Reservation savedReservation = captor.getValue();
        assertEquals(ReservationStatus.PENDING, savedReservation.getStatus());
        assertNotNull(savedReservation.getExpiresAt());
        assertFalse(savedReservation.getExpiresAt().isBefore(savedReservation.getReservedAt()));
    }

    @Test
    @DisplayName("createReservation: 사용자가 없으면 예외가 발생한다")
    void createReservationUserNotFound() {
        when(userRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> reservationService.createReservation(new CreateReservationCommand(1L, 10L)));

        verify(seatRepository, never()).findById(anyLong());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("createReservation: 좌석이 없으면 예외가 발생한다")
    void createReservationSeatNotFound() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(createUser()));
        when(seatRepository.findById(10L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> reservationService.createReservation(new CreateReservationCommand(1L, 10L)));

        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("createReservation: AVAILABLE이 아닌 좌석은 예약할 수 없다")
    void createReservationSeatNotAvailable() {
        User user = createUser();
        Seat seat = createSeat();
        seat.hold();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(seatRepository.findById(10L)).thenReturn(Optional.of(seat));

        assertThrows(IllegalStateException.class,
                () -> reservationService.createReservation(new CreateReservationCommand(1L, 10L)));

        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancelReservation: PENDING 예약을 취소하면 좌석이 AVAILABLE로 복구된다")
    void cancelReservationPendingSuccess() {
        Reservation reservation = createPendingReservationWithHeldSeat();
        when(reservationRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(reservation));

        reservationService.cancelReservation(1L, 1L);

        assertEquals(ReservationStatus.CANCELED, reservation.getStatus());
        assertEquals(SeatStatus.AVAILABLE, reservation.getSeat().getStatus());
    }

    @Test
    @DisplayName("cancelReservation: CONFIRMED 예약도 취소 가능하고 좌석이 복구된다")
    void cancelReservationConfirmedSuccess() {
        Reservation reservation = createPendingReservationWithHeldSeat();
        reservation.confirm();

        when(reservationRepository.findByIdAndUserId(2L, 2L)).thenReturn(Optional.of(reservation));

        reservationService.cancelReservation(2L, 2L);

        assertEquals(ReservationStatus.CANCELED, reservation.getStatus());
        assertEquals(SeatStatus.AVAILABLE, reservation.getSeat().getStatus());
    }

    @Test
    @DisplayName("cancelReservation: 다른 사용자의 예약이면 예외가 발생한다")
    void cancelReservationByAnotherUser() {
        when(reservationRepository.findByIdAndUserId(10L, 99L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> reservationService.cancelReservation(99L, 10L));
    }

    @Test
    @DisplayName("cancelReservation: 이미 CANCELED 상태면 취소할 수 없다")
    void cancelReservationAlreadyCanceled() {
        Reservation reservation = createPendingReservationWithHeldSeat();
        reservation.cancel();

        when(reservationRepository.findByIdAndUserId(1L, 3L)).thenReturn(Optional.of(reservation));

        assertThrows(IllegalStateException.class,
                () -> reservationService.cancelReservation(3L, 1L));
    }

    @Test
    @DisplayName("cancelReservation: EXPIRED 상태면 취소할 수 없다")
    void cancelReservationExpired() {
        Reservation reservation = createPendingReservationWithHeldSeat();
        ReflectionTestUtils.setField(reservation, "expiresAt", LocalDateTime.now().minusMinutes(1));
        reservation.expire();

        when(reservationRepository.findByIdAndUserId(1L, 4L)).thenReturn(Optional.of(reservation));

        assertThrows(IllegalStateException.class,
                () -> reservationService.cancelReservation(4L, 1L));
    }

    @Test
    @DisplayName("expireReservations: 만료된 예약은 EXPIRED로 변경되고 좌석이 복구된다")
    void expireReservationsSuccess() {
        Reservation expiredReservation = createPendingReservationWithHeldSeat();
        ReflectionTestUtils.setField(expiredReservation, "expiresAt", LocalDateTime.now().minusSeconds(1));

        when(reservationRepository.findByStatusAndExpiresAtBefore(eq(ReservationStatus.PENDING), any(LocalDateTime.class)))
                .thenReturn(List.of(expiredReservation));

        reservationService.expireReservations();

        assertEquals(ReservationStatus.EXPIRED, expiredReservation.getStatus());
        assertEquals(SeatStatus.AVAILABLE, expiredReservation.getSeat().getStatus());
    }

    @Test
    @DisplayName("expireReservations: 만료 대상이 없으면 상태가 유지된다")
    void expireReservationsNoTarget() {
        Reservation reservation = createPendingReservationWithHeldSeat();

        when(reservationRepository.findByStatusAndExpiresAtBefore(eq(ReservationStatus.PENDING), any(LocalDateTime.class)))
                .thenReturn(List.of());

        reservationService.expireReservations();

        assertEquals(ReservationStatus.PENDING, reservation.getStatus());
        assertEquals(SeatStatus.HELD, reservation.getSeat().getStatus());
    }

    private Reservation createPendingReservationWithHeldSeat() {
        Seat seat = createSeat();
        seat.hold();
        return new Reservation(createUser(), seat, LocalDateTime.now());
    }

    private Seat createSeat() {
        Event event = new Event("공연", "설명", "장소", LocalDateTime.now(), LocalDateTime.now());
        Schedule schedule = new Schedule(event, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(2), LocalDateTime.now());
        return new Seat(schedule, "A-1", "VIP", 100000, LocalDateTime.now());
    }

    private User createUser() {
        return new User("user@test.com", "password", "테스터", LocalDateTime.now());
    }
}

