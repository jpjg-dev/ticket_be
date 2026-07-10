package com.jipi.ticket_ledger.payment.application.recovery;

import com.jipi.ticket_ledger.event.domain.Event;
import com.jipi.ticket_ledger.event.domain.Schedule;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentLookupResponse;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.seat.domain.Seat;
import com.jipi.ticket_ledger.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private RecoverySnapshot snapshot(boolean held) {
        return new RecoverySnapshot(1L, "order-1", 11000, "KRW", held);
    }

    private TossPaymentLookupResponse lookup(String status, String orderId, Integer amount, String currency) {
        return new TossPaymentLookupResponse("pay-key-1", orderId, status, "CARD", amount, currency);
    }

    @Test
    @DisplayName("decide: DONE + 전 필드 일치 + 좌석 유효 → APPROVE")
    void doneMatchHeldApprove() {
        RecoveryDecision decision = RecoveryPolicy.decide(snapshot(true), lookup("DONE", "order-1", 11000, "KRW"));

        assertEquals(RecoveryAction.APPROVE, decision.action());
        assertNull(decision.refundReason());
    }

    @Test
    @DisplayName("decide: DONE + 전 필드 일치 + 좌석 유실 → REFUND_THEN_FAIL(SEAT_UNAVAILABLE)")
    void doneMatchNotHeldRefund() {
        RecoveryDecision decision = RecoveryPolicy.decide(snapshot(false), lookup("DONE", "order-1", 11000, "KRW"));

        assertEquals(RecoveryAction.REFUND_THEN_FAIL, decision.action());
        assertEquals("SEAT_UNAVAILABLE", decision.refundReason());
    }

    @Test
    @DisplayName("decide: CANCELED → FAIL")
    void canceledFail() {
        RecoveryDecision decision = RecoveryPolicy.decide(snapshot(true), lookup("CANCELED", "order-1", 11000, "KRW"));

        assertEquals(RecoveryAction.FAIL, decision.action());
    }

    @Test
    @DisplayName("decide: 비 DONE(IN_PROGRESS 등) → FAIL")
    void nonDoneFail() {
        RecoveryDecision decision = RecoveryPolicy.decide(snapshot(true), lookup("IN_PROGRESS", "order-1", 11000, "KRW"));

        assertEquals(RecoveryAction.FAIL, decision.action());
    }

    @Test
    @DisplayName("decide: DONE + orderId 불일치 → HOLD_MANUAL")
    void doneOrderIdMismatchHoldManual() {
        RecoveryDecision decision = RecoveryPolicy.decide(snapshot(true), lookup("DONE", "other-order", 11000, "KRW"));

        assertEquals(RecoveryAction.HOLD_MANUAL, decision.action());
    }

    @Test
    @DisplayName("decide: DONE + orderId 일치 + 금액 불일치 → REFUND_THEN_FAIL(PG_DATA_MISMATCH)")
    void doneAmountMismatchRefund() {
        RecoveryDecision decision = RecoveryPolicy.decide(snapshot(true), lookup("DONE", "order-1", 9999, "KRW"));

        assertEquals(RecoveryAction.REFUND_THEN_FAIL, decision.action());
        assertEquals("PG_DATA_MISMATCH", decision.refundReason());
    }

    @Test
    @DisplayName("decide: DONE + orderId 일치 + 통화 불일치 → REFUND_THEN_FAIL(PG_DATA_MISMATCH)")
    void doneCurrencyMismatchRefund() {
        RecoveryDecision decision = RecoveryPolicy.decide(snapshot(true), lookup("DONE", "order-1", 11000, "USD"));

        assertEquals(RecoveryAction.REFUND_THEN_FAIL, decision.action());
        assertEquals("PG_DATA_MISMATCH", decision.refundReason());
    }

    @Test
    @DisplayName("isReservationStillHeld: 모든 전제조건 참이면 true")
    void heldAllConditionsTrue() {
        ReservationGroup group = group(NOW.plusSeconds(300));
        Reservation reservation = pendingHeldReservation(group, NOW.plusSeconds(300));

        assertTrue(RecoveryPolicy.isReservationStillHeld(group, List.of(reservation), NOW));
    }

    @Test
    @DisplayName("isReservationStillHeld: 그룹이 PENDING 아니면 false")
    void heldFalseWhenGroupNotPending() {
        ReservationGroup group = group(NOW.plusSeconds(300));
        Reservation reservation = pendingHeldReservation(group, NOW.plusSeconds(300));
        group.confirm();

        assertFalse(RecoveryPolicy.isReservationStillHeld(group, List.of(reservation), NOW));
    }

    @Test
    @DisplayName("isReservationStillHeld: 예약이 PENDING 아니면 false")
    void heldFalseWhenReservationNotPending() {
        ReservationGroup group = group(NOW.plusSeconds(300));
        Reservation reservation = pendingHeldReservation(group, NOW.plusSeconds(300));
        reservation.confirm();

        assertFalse(RecoveryPolicy.isReservationStillHeld(group, List.of(reservation), NOW));
    }

    @Test
    @DisplayName("isReservationStillHeld: 좌석이 HELD 아니면 false")
    void heldFalseWhenSeatNotHeld() {
        ReservationGroup group = group(NOW.plusSeconds(300));
        Reservation reservation = pendingHeldReservation(group, NOW.plusSeconds(300));
        reservation.getSeat().book();

        assertFalse(RecoveryPolicy.isReservationStillHeld(group, List.of(reservation), NOW));
    }

    @Test
    @DisplayName("isReservationStillHeld: 만료 경계(expiresAt == now)면 false")
    void heldFalseWhenExpiredAtBoundary() {
        ReservationGroup group = group(NOW);
        Reservation reservation = pendingHeldReservation(group, NOW);

        assertFalse(RecoveryPolicy.isReservationStillHeld(group, List.of(reservation), NOW));
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
