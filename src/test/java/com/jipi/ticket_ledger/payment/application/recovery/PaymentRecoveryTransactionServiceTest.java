package com.jipi.ticket_ledger.payment.application.recovery;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentLookupResponse;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupStatus;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationStatus;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.seat.domain.SeatStatus;
import com.jipi.ticket_ledger.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentRecoveryTransactionServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final ReservationRepository reservationRepository = mock(ReservationRepository.class);
    private final PaymentRecoveryTransactionService transactionService =
            new PaymentRecoveryTransactionService(paymentRepository, reservationRepository, Clock.fixed(NOW, ZoneOffset.UTC));

    private final TossPaymentLookupResponse doneLookup =
            new TossPaymentLookupResponse("pay-key-1", "order-1", "DONE", "CARD", 11000, "KRW");

    @Test
    @DisplayName("loadRecoverySnapshot: CONFIRMING 결제만 원시 스냅샷으로 반환한다")
    void loadSnapshotConfirmingOnly() {
        ReservationGroup group = group(NOW.plusSeconds(300));
        Reservation reservation = pendingHeldReservation(group, NOW.plusSeconds(300));
        Payment payment = confirmingPayment(group, "order-1");
        when(paymentRepository.findById(10L)).thenReturn(Optional.of(payment));
        when(reservationRepository.findByReservationGroupIdWithSeat(group.getId())).thenReturn(List.of(reservation));

        RecoverySnapshot snapshot = transactionService.loadRecoverySnapshot(10L);

        assertEquals("order-1", snapshot.orderId());
        assertEquals(11000, snapshot.expectedAmount());
        assertEquals("KRW", snapshot.currency());
        assertEquals(true, snapshot.reservationHeld());
    }

    @Test
    @DisplayName("loadRecoverySnapshot: CONFIRMING 이 아니면 null")
    void loadSnapshotNullWhenNotConfirming() {
        ReservationGroup group = group(NOW.plusSeconds(300));
        Payment payment = new Payment(group, 10000, NOW, "order-1", "KRW"); // READY
        when(paymentRepository.findById(10L)).thenReturn(Optional.of(payment));

        assertNull(transactionService.loadRecoverySnapshot(10L));
    }

    @Test
    @DisplayName("applyDecision APPROVE + 락 하 좌석 유효 → 승인/확정/좌석 예매 후 APPROVED")
    void applyApprove() {
        ReservationGroup group = group(NOW.plusSeconds(300));
        Reservation reservation = pendingHeldReservation(group, NOW.plusSeconds(300));
        Payment payment = confirmingPayment(group, "order-1");
        when(paymentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(payment));
        when(reservationRepository.findByReservationGroupIdWithSeat(group.getId())).thenReturn(List.of(reservation));

        RecoveryOutcome outcome = transactionService.applyDecision(10L, RecoveryDecision.approve(), doneLookup);

        assertEquals(RecoveryOutcome.APPROVED, outcome);
        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
        assertEquals(ReservationGroupStatus.CONFIRMED, group.getStatus());
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
        assertEquals(SeatStatus.BOOKED, reservation.getSeat().getStatus());
    }

    @Test
    @DisplayName("applyDecision APPROVE 인데 락 후 CONFIRMING 아님 → 무쓰기 NOOP")
    void applyApproveButNotConfirming() {
        ReservationGroup group = group(NOW.plusSeconds(300));
        Payment payment = new Payment(group, 10000, NOW, "order-1", "KRW"); // READY

        when(paymentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(payment));

        RecoveryOutcome outcome = transactionService.applyDecision(10L, RecoveryDecision.approve(), doneLookup);

        assertEquals(RecoveryOutcome.NOOP_NOT_CONFIRMING, outcome);
        assertEquals(PaymentStatus.READY, payment.getStatus());
        verify(reservationRepository, never()).findByReservationGroupIdWithSeat(any());
    }

    @Test
    @DisplayName("applyDecision APPROVE 인데 락 후 좌석 유실 → 무쓰기 SEAT_LOST_DEFERRED")
    void applyApproveButSeatLost() {
        ReservationGroup group = group(NOW.plusSeconds(300));
        Reservation reservation = pendingHeldReservation(group, NOW.plusSeconds(300));
        reservation.getSeat().book(); // 좌석 유실 시뮬
        Payment payment = confirmingPayment(group, "order-1");
        when(paymentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(payment));
        when(reservationRepository.findByReservationGroupIdWithSeat(group.getId())).thenReturn(List.of(reservation));

        RecoveryOutcome outcome = transactionService.applyDecision(10L, RecoveryDecision.approve(), doneLookup);

        assertEquals(RecoveryOutcome.SEAT_LOST_DEFERRED, outcome);
        assertEquals(PaymentStatus.CONFIRMING, payment.getStatus());
        assertEquals(ReservationGroupStatus.PENDING, group.getStatus());
    }

    @Test
    @DisplayName("applyDecision FAIL → failAndRelease 후 FAILED_RELEASED")
    void applyFail() {
        ReservationGroup group = group(NOW.plusSeconds(300));
        Reservation reservation = pendingHeldReservation(group, NOW.plusSeconds(300));
        Payment payment = confirmingPayment(group, "order-1");
        when(paymentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(payment));
        when(reservationRepository.findByReservationGroupIdWithSeat(group.getId())).thenReturn(List.of(reservation));

        RecoveryOutcome outcome = transactionService.applyDecision(10L, RecoveryDecision.fail(), doneLookup);

        assertEquals(RecoveryOutcome.FAILED_RELEASED, outcome);
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertEquals(ReservationGroupStatus.EXPIRED, group.getStatus());
        assertEquals(ReservationStatus.EXPIRED, reservation.getStatus());
        assertEquals(SeatStatus.AVAILABLE, reservation.getSeat().getStatus());
    }

    @Test
    @DisplayName("applyDecision REFUND_THEN_FAIL → failAndRelease 만(환불은 이미 밖에서 끝남) 후 REFUNDED_FAILED")
    void applyRefundThenFail() {
        ReservationGroup group = group(NOW.plusSeconds(300));
        Reservation reservation = pendingHeldReservation(group, NOW.plusSeconds(300));
        Payment payment = confirmingPayment(group, "order-1");
        when(paymentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(payment));
        when(reservationRepository.findByReservationGroupIdWithSeat(group.getId())).thenReturn(List.of(reservation));

        RecoveryOutcome outcome =
                transactionService.applyDecision(10L, RecoveryDecision.refundThenFail("SEAT_UNAVAILABLE"), doneLookup);

        assertEquals(RecoveryOutcome.REFUNDED_FAILED, outcome);
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertEquals(SeatStatus.AVAILABLE, reservation.getSeat().getStatus());
    }

    @Test
    @DisplayName("applyDecision: payment 없음 → NOOP")
    void applyPaymentNull() {
        when(paymentRepository.findByIdForUpdate(10L)).thenReturn(Optional.empty());

        RecoveryOutcome outcome = transactionService.applyDecision(10L, RecoveryDecision.approve(), doneLookup);

        assertEquals(RecoveryOutcome.NOOP_NOT_CONFIRMING, outcome);
    }

    private Payment confirmingPayment(ReservationGroup group, String orderId) {
        Payment payment = new Payment(group, 10000, NOW, orderId, "KRW");
        payment.confirming();
        return payment;
    }

    private ReservationGroup group(Instant expiresAt) {
        User user = new User("user@test.com", "password", "name", LocalDateTime.now());
        return new ReservationGroup(user, NOW, expiresAt);
    }

    private Reservation pendingHeldReservation(ReservationGroup group, Instant expiresAt) {
        Event event = new Event("공연", "설명", "장소", LocalDateTime.now(), LocalDateTime.now().plusDays(1));
        Schedule schedule = new Schedule(
                event,
                LocalDateTime.now().plusDays(1),
                LocalDateTime.now().plusDays(1).plusHours(2),
                LocalDateTime.now()
        );
        Seat seat = new Seat(schedule, "A-1", "VIP", 10000, LocalDateTime.now());
        seat.hold();
        return new Reservation(group.getUser(), seat, group, LocalDateTime.now(), expiresAt);
    }
}
