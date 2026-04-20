package com.jipi.ticket_ledger.reservation.application;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
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
import org.junit.jupiter.api.BeforeEach;
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

    @Mock
    private PaymentRepository paymentRepository;

    @InjectMocks
    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        lenient().when(reservationRepository.findByStatusAndExpiresAtLessThanEqual(eq(ReservationStatus.PENDING), any(LocalDateTime.class)))
                .thenReturn(List.of());
        lenient().when(paymentRepository.findByReservationId(anyLong())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("createReservation: 정상적으로 예약 생성되고 좌석/예약 상태가 변경된다")
    void createReservationSuccess() {
        User user = createUser();
        Seat seat = createSeat();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(seatRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(seat));
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

        verify(seatRepository, never()).findByIdForUpdate(anyLong());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("createReservation: 좌석이 없으면 예외가 발생한다")
    void createReservationSeatNotFound() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(createUser()));
        when(seatRepository.findByIdForUpdate(10L)).thenReturn(Optional.empty());

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
        when(seatRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(seat));

        assertThrows(IllegalStateException.class,
                () -> reservationService.createReservation(new CreateReservationCommand(1L, 10L)));

        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("expireReservations: 만료된 예약은 EXPIRED로 변경되고 좌석이 복구된다")
    void expireReservationsSuccess() {
        Reservation expiredReservation = createPendingReservationWithHeldSeat();
        ReflectionTestUtils.setField(expiredReservation, "expiresAt", LocalDateTime.now().minusSeconds(1));
        ReflectionTestUtils.setField(expiredReservation, "id", 1L);

        Payment payment = new Payment(expiredReservation, 10000, LocalDateTime.now(), "order-expire-1", "KRW");

        when(reservationRepository.findByStatusAndExpiresAtLessThanEqual(eq(ReservationStatus.PENDING), any(LocalDateTime.class)))
                .thenReturn(List.of(expiredReservation));
        when(paymentRepository.findByReservationId(1L)).thenReturn(Optional.of(payment));

        reservationService.expireReservations();

        assertEquals(ReservationStatus.EXPIRED, expiredReservation.getStatus());
        assertEquals(SeatStatus.AVAILABLE, expiredReservation.getSeat().getStatus());
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
    }

    @Test
    @DisplayName("expireReservations: 연결 결제가 READY가 아니면 결제 상태는 변경하지 않는다")
    void expireReservationsWithApprovedPayment() {
        Reservation expiredReservation = createPendingReservationWithHeldSeat();
        ReflectionTestUtils.setField(expiredReservation, "expiresAt", LocalDateTime.now().minusSeconds(1));
        ReflectionTestUtils.setField(expiredReservation, "id", 2L);

        Payment payment = new Payment(expiredReservation, 10000, LocalDateTime.now(), "order-expire-2", "KRW");
        payment.fail();

        when(reservationRepository.findByStatusAndExpiresAtLessThanEqual(eq(ReservationStatus.PENDING), any(LocalDateTime.class)))
                .thenReturn(List.of(expiredReservation));
        when(paymentRepository.findByReservationId(2L)).thenReturn(Optional.of(payment));

        reservationService.expireReservations();

        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertEquals(ReservationStatus.EXPIRED, expiredReservation.getStatus());
        assertEquals(SeatStatus.AVAILABLE, expiredReservation.getSeat().getStatus());
    }

    @Test
    @DisplayName("expireReservations: 만료 대상이 없으면 상태가 유지된다")
    void expireReservationsNoTarget() {
        Reservation reservation = createPendingReservationWithHeldSeat();

        when(reservationRepository.findByStatusAndExpiresAtLessThanEqual(eq(ReservationStatus.PENDING), any(LocalDateTime.class)))
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

