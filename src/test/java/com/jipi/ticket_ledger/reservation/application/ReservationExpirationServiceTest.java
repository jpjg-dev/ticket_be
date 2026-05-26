package com.jipi.ticket_ledger.reservation.application;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationStatus;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.seat.domain.SeatStatus;
import com.jipi.ticket_ledger.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationExpirationServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private ReservationGroupRepository reservationGroupRepository;

    @InjectMocks
    private ReservationExpirationService reservationExpirationService;

    @Test
    @DisplayName("expireAll: 만료된 group 안의 모든 예약, 좌석, READY 결제를 함께 만료 처리한다")
    void expireAllForReservationGroup() {
        Fixture fixture = createFixture(3L);

        when(reservationGroupRepository.findByExpiresAtLessThanEqual(any(LocalDateTime.class)))
                .thenReturn(List.of(fixture.group()));
        when(reservationRepository.findByReservationGroupId(3L)).thenReturn(fixture.reservations());
        when(paymentRepository.findByReservationGroupId(3L)).thenReturn(Optional.of(fixture.payment()));

        int expiredCount = reservationExpirationService.expireAll();

        assertEquals(2, expiredCount);
        assertEquals(PaymentStatus.FAILED, fixture.payment().getStatus());
        assertEquals(ReservationStatus.EXPIRED, fixture.reservations().get(0).getStatus());
        assertEquals(ReservationStatus.EXPIRED, fixture.reservations().get(1).getStatus());
        assertEquals(SeatStatus.AVAILABLE, fixture.reservations().get(0).getSeat().getStatus());
        assertEquals(SeatStatus.AVAILABLE, fixture.reservations().get(1).getSeat().getStatus());
    }

    @Test
    @DisplayName("expireByScheduleId: 조회 중인 회차의 만료 group만 대상으로 처리한다")
    void expireByScheduleIdTargetsOnlyRequestedSchedule() {
        Fixture fixture = createFixture(7L);

        when(reservationGroupRepository.findExpiredByScheduleId(eq(99L), any(LocalDateTime.class)))
                .thenReturn(List.of(fixture.group()));
        when(reservationRepository.findByReservationGroupId(7L)).thenReturn(fixture.reservations());
        when(paymentRepository.findByReservationGroupId(7L)).thenReturn(Optional.of(fixture.payment()));

        int expiredCount = reservationExpirationService.expireByScheduleId(99L);

        assertEquals(2, expiredCount);
        assertEquals(ReservationStatus.EXPIRED, fixture.reservations().get(0).getStatus());
        verify(reservationGroupRepository).findExpiredByScheduleId(eq(99L), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("expireAll: 연결 결제가 READY가 아니면 결제 상태는 변경하지 않는다")
    void expireAllDoesNotChangeAlreadyFailedPayment() {
        Fixture fixture = createFixture(8L);
        fixture.payment().fail();

        when(reservationGroupRepository.findByExpiresAtLessThanEqual(any(LocalDateTime.class)))
                .thenReturn(List.of(fixture.group()));
        when(reservationRepository.findByReservationGroupId(8L)).thenReturn(fixture.reservations());
        when(paymentRepository.findByReservationGroupId(8L)).thenReturn(Optional.of(fixture.payment()));

        int expiredCount = reservationExpirationService.expireAll();

        assertEquals(2, expiredCount);
        assertEquals(PaymentStatus.FAILED, fixture.payment().getStatus());
        assertEquals(ReservationStatus.EXPIRED, fixture.reservations().get(0).getStatus());
    }

    @Test
    @DisplayName("expireAll: 만료 대상이 없으면 상태 변경이 없다")
    void expireAllDoesNothingWithoutExpiredGroups() {
        Fixture fixture = createFixture(9L);
        when(reservationGroupRepository.findByExpiresAtLessThanEqual(any(LocalDateTime.class)))
                .thenReturn(List.of());

        int expiredCount = reservationExpirationService.expireAll();

        assertEquals(0, expiredCount);
        assertEquals(PaymentStatus.READY, fixture.payment().getStatus());
        assertEquals(ReservationStatus.PENDING, fixture.reservations().get(0).getStatus());
        assertEquals(SeatStatus.HELD, fixture.reservations().get(0).getSeat().getStatus());
    }

    private Fixture createFixture(Long groupId) {
        User user = new User("user@test.com", "password", "테스터", LocalDateTime.now());
        LocalDateTime now = LocalDateTime.now();
        ReservationGroup group = new ReservationGroup(user, now, now.minusSeconds(1));
        ReflectionTestUtils.setField(group, "id", groupId);

        Event event = new Event("공연", "설명", "장소", now, now);
        Schedule schedule = new Schedule(event, now.plusDays(1), now.plusDays(1).plusHours(2), now);
        Seat seat1 = new Seat(schedule, "A-1", "VIP", 100000, now);
        Seat seat2 = new Seat(schedule, "A-2", "VIP", 100000, now);
        seat1.hold();
        seat2.hold();

        Reservation reservation1 = new Reservation(user, seat1, group, now, group.getExpiresAt());
        Reservation reservation2 = new Reservation(user, seat2, group, now, group.getExpiresAt());
        Payment payment = new Payment(group, 200000, now, "order-expire-group", "KRW");

        return new Fixture(group, List.of(reservation1, reservation2), payment);
    }

    private record Fixture(ReservationGroup group, List<Reservation> reservations, Payment payment) {
    }
}
