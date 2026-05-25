package com.jipi.ticket_ledger.reservation.application;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.event.domain.ScheduleRepository;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
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
    private ScheduleRepository scheduleRepository;

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private ReservationGroupRepository reservationGroupRepository;

    @InjectMocks
    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(reservationService, "holdDuration", Duration.ofMinutes(5));
        lenient().when(reservationGroupRepository.findByExpiresAtLessThanEqual(any(LocalDateTime.class)))
                .thenReturn(List.of());
        lenient().when(paymentRepository.findByReservationGroupId(anyLong())).thenReturn(Optional.empty());
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
        assertNotNull(savedReservation.getExpiresAt());
        assertEquals(savedReservation.getReservedAt().plus(Duration.ofMinutes(5)), savedReservation.getExpiresAt());
        assertEquals(savedReservation.getReservationGroup().getExpiresAt(), savedReservation.getExpiresAt());
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
    @DisplayName("expireReservations: 만료된 예약은 EXPIRED로 변경되고 좌석이 복구된다")
    void expireReservationsSuccess() {
        Reservation expiredReservation = createPendingReservationWithHeldSeat();
        ReservationGroup reservationGroup = expiredReservation.getReservationGroup();
        ReflectionTestUtils.setField(expiredReservation, "expiresAt", LocalDateTime.now().minusSeconds(1));
        ReflectionTestUtils.setField(expiredReservation, "id", 1L);

        Payment payment = new Payment(reservationGroup, 10000, LocalDateTime.now(), "order-expire-1", "KRW");

        when(reservationGroupRepository.findByExpiresAtLessThanEqual(any(LocalDateTime.class)))
                .thenReturn(List.of(reservationGroup));
        when(reservationRepository.findByReservationGroupId(reservationGroup.getId())).thenReturn(List.of(expiredReservation));
        when(paymentRepository.findByReservationGroupId(reservationGroup.getId())).thenReturn(Optional.of(payment));

        int expiredCount = reservationService.expireReservations();

        assertEquals(ReservationStatus.EXPIRED, expiredReservation.getStatus());
        assertEquals(SeatStatus.AVAILABLE, expiredReservation.getSeat().getStatus());
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertEquals(1, expiredCount);
    }

    @Test
    @DisplayName("expireReservations: 연결 결제가 READY가 아니면 결제 상태는 변경하지 않는다")
    void expireReservationsWithApprovedPayment() {
        Reservation expiredReservation = createPendingReservationWithHeldSeat();
        ReservationGroup reservationGroup = expiredReservation.getReservationGroup();
        ReflectionTestUtils.setField(expiredReservation, "expiresAt", LocalDateTime.now().minusSeconds(1));
        ReflectionTestUtils.setField(expiredReservation, "id", 2L);

        Payment payment = new Payment(reservationGroup, 10000, LocalDateTime.now(), "order-expire-2", "KRW");
        payment.fail();

        when(reservationGroupRepository.findByExpiresAtLessThanEqual(any(LocalDateTime.class)))
                .thenReturn(List.of(reservationGroup));
        when(reservationRepository.findByReservationGroupId(reservationGroup.getId())).thenReturn(List.of(expiredReservation));
        when(paymentRepository.findByReservationGroupId(reservationGroup.getId())).thenReturn(Optional.of(payment));

        int expiredCount = reservationService.expireReservations();

        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertEquals(ReservationStatus.EXPIRED, expiredReservation.getStatus());
        assertEquals(SeatStatus.AVAILABLE, expiredReservation.getSeat().getStatus());
        assertEquals(1, expiredCount);
    }

    @Test
    @DisplayName("expireReservations: 만료된 group 안의 모든 예약과 좌석을 함께 만료 처리한다")
    void expireReservationsForReservationGroup() {
        User user = createUser();
        LocalDateTime now = LocalDateTime.now();
        ReservationGroup reservationGroup = new ReservationGroup(user, now, now.minusSeconds(1));
        ReflectionTestUtils.setField(reservationGroup, "id", 3L);

        Seat seat1 = createSeat();
        Seat seat2 = createSeat();
        seat1.hold();
        seat2.hold();
        Reservation reservation1 = new Reservation(user, seat1, reservationGroup, now, reservationGroup.getExpiresAt());
        Reservation reservation2 = new Reservation(user, seat2, reservationGroup, now, reservationGroup.getExpiresAt());
        Payment payment = new Payment(reservationGroup, 200000, LocalDateTime.now(), "order-expire-group", "KRW");

        when(reservationGroupRepository.findByExpiresAtLessThanEqual(any(LocalDateTime.class)))
                .thenReturn(List.of(reservationGroup));
        when(reservationRepository.findByReservationGroupId(3L)).thenReturn(List.of(reservation1, reservation2));
        when(paymentRepository.findByReservationGroupId(3L)).thenReturn(Optional.of(payment));

        int expiredCount = reservationService.expireReservations();

        assertEquals(2, expiredCount);
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertEquals(ReservationStatus.EXPIRED, reservation1.getStatus());
        assertEquals(ReservationStatus.EXPIRED, reservation2.getStatus());
        assertEquals(SeatStatus.AVAILABLE, seat1.getStatus());
        assertEquals(SeatStatus.AVAILABLE, seat2.getStatus());
    }

    @Test
    @DisplayName("expireReservations: 만료 대상이 없으면 상태가 유지된다")
    void expireReservationsNoTarget() {
        Reservation reservation = createPendingReservationWithHeldSeat();

        when(reservationGroupRepository.findByExpiresAtLessThanEqual(any(LocalDateTime.class)))
                .thenReturn(List.of());

        int expiredCount = reservationService.expireReservations();

        assertEquals(ReservationStatus.PENDING, reservation.getStatus());
        assertEquals(SeatStatus.HELD, reservation.getSeat().getStatus());
        assertEquals(0, expiredCount);
    }

    private Reservation createPendingReservationWithHeldSeat() {
        Seat seat = createSeat();
        seat.hold();
        LocalDateTime now = LocalDateTime.now();
        ReservationGroup reservationGroup = new ReservationGroup(createUser(), now, now.minusSeconds(1));
        ReflectionTestUtils.setField(reservationGroup, "id", 1L);
        return new Reservation(createUser(), seat, reservationGroup, now, reservationGroup.getExpiresAt());
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

