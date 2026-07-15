package com.jipi.ticket_ledger.reservation.application;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupStatus;
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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationExpirationTransactionServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private ReservationGroupRepository reservationGroupRepository;

    @Spy
    private Clock clock = Clock.systemDefaultZone();

    @InjectMocks
    private ReservationExpirationTransactionService transactionService;

    @Test
    @DisplayName("expireGroup: 예약, 좌석, READY 결제를 함께 만료 처리한다")
    void expiresReservationGroup() {
        Fixture fixture = createFixture(3L);
        prepare(fixture);

        int expiredCount = transactionService.expireGroup(3L, "SCHEDULER");

        assertEquals(2, expiredCount);
        assertEquals(ReservationGroupStatus.EXPIRED, fixture.group().getStatus());
        assertEquals(PaymentStatus.FAILED, fixture.payment().getStatus());
        assertEquals(ReservationStatus.EXPIRED, fixture.reservations().getFirst().getStatus());
        assertEquals(SeatStatus.AVAILABLE, fixture.reservations().getFirst().getSeat().getStatus());
    }

    @Test
    @DisplayName("expireGroup: Payment, ReservationGroup 순서로 락을 획득한다")
    void locksPaymentBeforeReservationGroup() {
        Fixture fixture = createFixture(13L);
        prepare(fixture);

        transactionService.expireGroup(13L, "SCHEDULER");

        var inOrder = inOrder(paymentRepository, reservationGroupRepository, reservationRepository);
        inOrder.verify(paymentRepository).findByReservationGroupIdForUpdate(13L);
        inOrder.verify(reservationGroupRepository).findByIdForUpdate(13L);
        inOrder.verify(reservationRepository).findByReservationGroupIdWithSeat(13L);
    }

    @Test
    @DisplayName("expireGroup: CONFIRMING 결제는 보정 스케줄러에 맡기고 건너뛴다")
    void skipsConfirmingPayment() {
        Fixture fixture = createFixture(12L);
        fixture.payment().confirming();
        when(paymentRepository.findByReservationGroupIdForUpdate(12L)).thenReturn(Optional.of(fixture.payment()));
        when(reservationGroupRepository.findByIdForUpdate(12L)).thenReturn(Optional.of(fixture.group()));

        int expiredCount = transactionService.expireGroup(12L, "SCHEDULER");

        assertEquals(0, expiredCount);
        assertEquals(ReservationGroupStatus.PENDING, fixture.group().getStatus());
        assertEquals(SeatStatus.HELD, fixture.reservations().getFirst().getSeat().getStatus());
        verify(reservationRepository, never()).findByReservationGroupIdWithSeat(12L);
    }

    @Test
    @DisplayName("expireGroup: 결제가 없어도 예약 그룹을 만료 처리한다")
    void expiresGroupWithoutPayment() {
        Fixture fixture = createFixture(11L);
        when(paymentRepository.findByReservationGroupIdForUpdate(11L)).thenReturn(Optional.empty());
        when(reservationGroupRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(fixture.group()));
        when(reservationRepository.findByReservationGroupIdWithSeat(11L)).thenReturn(fixture.reservations());

        int expiredCount = transactionService.expireGroup(11L, "SCHEDULER");

        assertEquals(2, expiredCount);
        assertEquals(ReservationGroupStatus.EXPIRED, fixture.group().getStatus());
    }

    @Test
    @DisplayName("expireGroup: 락 획득 전 이미 만료된 그룹은 다시 처리하지 않는다")
    void skipsAlreadyExpiredGroup() {
        Fixture fixture = createFixture(9L);
        fixture.group().expire();
        when(paymentRepository.findByReservationGroupIdForUpdate(9L)).thenReturn(Optional.of(fixture.payment()));
        when(reservationGroupRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(fixture.group()));

        int expiredCount = transactionService.expireGroup(9L, "SCHEDULER");

        assertEquals(0, expiredCount);
        assertEquals(SeatStatus.HELD, fixture.reservations().getFirst().getSeat().getStatus());
        verify(reservationRepository, never()).findByReservationGroupIdWithSeat(9L);
    }

    private void prepare(Fixture fixture) {
        Long groupId = fixture.group().getId();
        when(paymentRepository.findByReservationGroupIdForUpdate(groupId)).thenReturn(Optional.of(fixture.payment()));
        when(reservationGroupRepository.findByIdForUpdate(groupId)).thenReturn(Optional.of(fixture.group()));
        when(reservationRepository.findByReservationGroupIdWithSeat(groupId)).thenReturn(fixture.reservations());
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
