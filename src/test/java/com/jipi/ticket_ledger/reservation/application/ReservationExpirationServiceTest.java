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
import org.springframework.data.domain.Pageable;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
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

    // 기본은 시스템 시계라 기존 테스트 동작이 그대로 유지된다. 시간 판정을 고정하려면 테스트별로 Clock.fixed 로 교체한다.
    @Spy
    private Clock clock = Clock.systemDefaultZone();

    @InjectMocks
    private ReservationExpirationService reservationExpirationService;

    @Test
    @DisplayName("expireAll: 주입된 Clock의 시각을 만료 기준으로 사용한다(시간 결정성)")
    void expireAllUsesInjectedClockInstant() {
        Instant fixedNow = Instant.parse("2026-01-01T00:00:00Z");
        ReflectionTestUtils.setField(reservationExpirationService, "clock", Clock.fixed(fixedNow, ZoneOffset.UTC));
        when(reservationGroupRepository.findExpiredPendingIds(eq(fixedNow), any(Pageable.class)))
                .thenReturn(List.of());

        reservationExpirationService.expireAll();

        // 서비스가 실시간 Instant.now() 가 아니라 주입된 Clock 시각을 만료 컷오프로 넘겼는지 검증한다.
        verify(reservationGroupRepository).findExpiredPendingIds(eq(fixedNow), any(Pageable.class));
    }

    @Test
    @DisplayName("expireAll: 만료된 group 안의 모든 예약, 좌석, READY 결제를 함께 만료 처리한다")
    void expireAllForReservationGroup() {
        Fixture fixture = createFixture(3L);

        when(reservationGroupRepository.findExpiredPendingIds(any(java.time.Instant.class), any(Pageable.class)))
                .thenReturn(List.of(3L));
        when(paymentRepository.findByReservationGroupIdForUpdate(3L)).thenReturn(Optional.of(fixture.payment()));
        when(reservationGroupRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(fixture.group()));
        when(reservationRepository.findByReservationGroupIdWithSeat(3L)).thenReturn(fixture.reservations());

        int expiredCount = reservationExpirationService.expireAll();

        assertEquals(2, expiredCount);
        assertEquals(ReservationGroupStatus.EXPIRED, fixture.group().getStatus());
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

        when(reservationGroupRepository.findExpiredPendingIdsByScheduleId(eq(99L), any(java.time.Instant.class)))
                .thenReturn(List.of(7L));
        when(paymentRepository.findByReservationGroupIdForUpdate(7L)).thenReturn(Optional.of(fixture.payment()));
        when(reservationGroupRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(fixture.group()));
        when(reservationRepository.findByReservationGroupIdWithSeat(7L)).thenReturn(fixture.reservations());

        int expiredCount = reservationExpirationService.expireByScheduleId(99L);

        assertEquals(2, expiredCount);
        assertEquals(ReservationGroupStatus.EXPIRED, fixture.group().getStatus());
        assertEquals(ReservationStatus.EXPIRED, fixture.reservations().get(0).getStatus());
        verify(reservationGroupRepository).findExpiredPendingIdsByScheduleId(eq(99L), any(java.time.Instant.class));
        var inOrder = inOrder(paymentRepository, reservationGroupRepository, reservationRepository);
        inOrder.verify(paymentRepository).findByReservationGroupIdForUpdate(7L);
        inOrder.verify(reservationGroupRepository).findByIdForUpdate(7L);
        inOrder.verify(reservationRepository).findByReservationGroupIdWithSeat(7L);
    }

    @Test
    @DisplayName("expireAll: Payment, ReservationGroup 순서로 락을 획득한 뒤 예약과 좌석을 조회한다")
    void expireAllLocksPaymentBeforeReservationGroup() {
        Fixture fixture = createFixture(13L);

        when(reservationGroupRepository.findExpiredPendingIds(any(java.time.Instant.class), any(Pageable.class)))
                .thenReturn(List.of(13L));
        when(paymentRepository.findByReservationGroupIdForUpdate(13L)).thenReturn(Optional.of(fixture.payment()));
        when(reservationGroupRepository.findByIdForUpdate(13L)).thenReturn(Optional.of(fixture.group()));
        when(reservationRepository.findByReservationGroupIdWithSeat(13L)).thenReturn(fixture.reservations());

        reservationExpirationService.expireAll();

        var inOrder = inOrder(paymentRepository, reservationGroupRepository, reservationRepository);
        inOrder.verify(paymentRepository).findByReservationGroupIdForUpdate(13L);
        inOrder.verify(reservationGroupRepository).findByIdForUpdate(13L);
        inOrder.verify(reservationRepository).findByReservationGroupIdWithSeat(13L);
    }

    @Test
    @DisplayName("expireAll: 연결 결제가 READY가 아니면 결제 상태는 변경하지 않는다")
    void expireAllDoesNotChangeAlreadyFailedPayment() {
        Fixture fixture = createFixture(8L);
        fixture.payment().fail();

        when(reservationGroupRepository.findExpiredPendingIds(any(java.time.Instant.class), any(Pageable.class)))
                .thenReturn(List.of(8L));
        when(paymentRepository.findByReservationGroupIdForUpdate(8L)).thenReturn(Optional.of(fixture.payment()));
        when(reservationGroupRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(fixture.group()));
        when(reservationRepository.findByReservationGroupIdWithSeat(8L)).thenReturn(fixture.reservations());

        int expiredCount = reservationExpirationService.expireAll();

        assertEquals(2, expiredCount);
        assertEquals(ReservationGroupStatus.EXPIRED, fixture.group().getStatus());
        assertEquals(PaymentStatus.FAILED, fixture.payment().getStatus());
        assertEquals(ReservationStatus.EXPIRED, fixture.reservations().get(0).getStatus());
    }

    @Test
    @DisplayName("expireAll: CONFIRMING 결제가 연결된 group은 보정 스케줄러가 처리하도록 만료하지 않는다")
    void expireAllSkipsConfirmingPayment() {
        Fixture fixture = createFixture(12L);
        fixture.payment().confirming();

        when(reservationGroupRepository.findExpiredPendingIds(any(java.time.Instant.class), any(Pageable.class)))
                .thenReturn(List.of(12L));
        when(paymentRepository.findByReservationGroupIdForUpdate(12L)).thenReturn(Optional.of(fixture.payment()));
        when(reservationGroupRepository.findByIdForUpdate(12L)).thenReturn(Optional.of(fixture.group()));

        int expiredCount = reservationExpirationService.expireAll();

        assertEquals(0, expiredCount);
        assertEquals(PaymentStatus.CONFIRMING, fixture.payment().getStatus());
        assertEquals(ReservationGroupStatus.PENDING, fixture.group().getStatus());
        assertEquals(ReservationStatus.PENDING, fixture.reservations().get(0).getStatus());
        assertEquals(SeatStatus.HELD, fixture.reservations().get(0).getSeat().getStatus());
        verify(reservationRepository, never()).findByReservationGroupIdWithSeat(12L);
    }

    @Test
    @DisplayName("expireAll: 결제가 없는 만료 group은 group 락을 획득한 뒤 처리한다")
    void expireAllLocksGroupWhenPaymentDoesNotExist() {
        Fixture fixture = createFixture(11L);

        when(reservationGroupRepository.findExpiredPendingIds(any(java.time.Instant.class), any(Pageable.class)))
                .thenReturn(List.of(11L));
        when(paymentRepository.findByReservationGroupIdForUpdate(11L)).thenReturn(Optional.empty());
        when(reservationGroupRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(fixture.group()));
        when(reservationRepository.findByReservationGroupIdWithSeat(11L)).thenReturn(fixture.reservations());

        int expiredCount = reservationExpirationService.expireAll();

        assertEquals(2, expiredCount);
        assertEquals(ReservationGroupStatus.EXPIRED, fixture.group().getStatus());
        assertEquals(SeatStatus.AVAILABLE, fixture.reservations().get(0).getSeat().getStatus());
        verify(reservationGroupRepository).findByIdForUpdate(11L);
    }

    @Test
    @DisplayName("expireAll: 락 대기 중 이미 만료된 group은 좌석을 다시 해제하지 않는다")
    void expireAllSkipsGroupExpiredBeforeLockedProcessing() {
        Fixture fixture = createFixture(9L);
        fixture.group().expire();

        when(reservationGroupRepository.findExpiredPendingIds(any(java.time.Instant.class), any(Pageable.class)))
                .thenReturn(List.of(9L));
        when(paymentRepository.findByReservationGroupIdForUpdate(9L)).thenReturn(Optional.of(fixture.payment()));
        when(reservationGroupRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(fixture.group()));

        int expiredCount = reservationExpirationService.expireAll();

        assertEquals(0, expiredCount);
        assertEquals(ReservationGroupStatus.EXPIRED, fixture.group().getStatus());
        assertEquals(SeatStatus.HELD, fixture.reservations().get(0).getSeat().getStatus());
        verify(reservationRepository, never()).findByReservationGroupIdWithSeat(9L);
    }

    @Test
    @DisplayName("expireAll: 만료 대상이 없으면 상태 변경이 없다")
    void expireAllDoesNothingWithoutExpiredGroups() {
        Fixture fixture = createFixture(10L);
        when(reservationGroupRepository.findExpiredPendingIds(any(java.time.Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        int expiredCount = reservationExpirationService.expireAll();

        assertEquals(0, expiredCount);
        assertEquals(ReservationGroupStatus.PENDING, fixture.group().getStatus());
        assertEquals(PaymentStatus.READY, fixture.payment().getStatus());
        assertEquals(ReservationStatus.PENDING, fixture.reservations().get(0).getStatus());
        assertEquals(SeatStatus.HELD, fixture.reservations().get(0).getSeat().getStatus());
    }

    @Test
    @DisplayName("expireAll: 설정된 batch-size 만큼만 만료 후보를 조회한다")
    void expireAllUsesConfiguredBatchSize() {
        ReflectionTestUtils.setField(reservationExpirationService, "batchSize", 2);
        when(reservationGroupRepository.findExpiredPendingIds(any(java.time.Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        reservationExpirationService.expireAll();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(reservationGroupRepository).findExpiredPendingIds(any(java.time.Instant.class), pageableCaptor.capture());
        assertEquals(2, pageableCaptor.getValue().getPageSize());
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
