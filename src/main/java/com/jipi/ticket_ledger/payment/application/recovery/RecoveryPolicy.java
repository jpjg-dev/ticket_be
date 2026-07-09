package com.jipi.ticket_ledger.payment.application.recovery;

import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentLookupResponse;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentStatus;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupStatus;
import com.jipi.ticket_ledger.reservation.domain.ReservationStatus;
import com.jipi.ticket_ledger.seat.domain.SeatStatus;

import java.time.Instant;
import java.util.List;

/**
 * CONFIRMING 회색지대 보정의 결정 로직. 순수 함수만 담아 트랜잭션/외부호출과 분리한다.
 */
final class RecoveryPolicy {

    private RecoveryPolicy() {
    }

    /**
     * 스냅샷과 PG 조회 결과로 어떤 조치를 취할지 결정한다(부수효과 없음).
     * <ul>
     *   <li>PG 미승인(비 DONE) → FAIL</li>
     *   <li>DONE + orderId 불일치 → HOLD_MANUAL</li>
     *   <li>DONE + orderId 일치 + 금액/통화 불일치 → REFUND_THEN_FAIL(PG_DATA_MISMATCH)</li>
     *   <li>DONE + 전 필드 일치 + 좌석 유효 → APPROVE</li>
     *   <li>DONE + 전 필드 일치 + 좌석 유실 → REFUND_THEN_FAIL(SEAT_UNAVAILABLE)</li>
     * </ul>
     */
    static RecoveryDecision decide(RecoverySnapshot snapshot, TossPaymentLookupResponse lookup) {
        if (!TossPaymentStatus.isApproved(lookup.status())) {
            return RecoveryDecision.fail();
        }

        if (!snapshot.orderId().equals(lookup.orderId())) {
            return RecoveryDecision.holdManual();
        }

        boolean amountMatch = snapshot.expectedAmount().equals(lookup.totalAmount());
        boolean currencyMatch = snapshot.currency().equals(lookup.currency());
        if (!amountMatch || !currencyMatch) {
            return RecoveryDecision.refundThenFail("PG_DATA_MISMATCH");
        }

        if (snapshot.reservationHeld()) {
            return RecoveryDecision.approve();
        }
        return RecoveryDecision.refundThenFail("SEAT_UNAVAILABLE");
    }

    /**
     * 좌석 선점이 여전히 유효한지 판정하는 술어. 스냅샷 빌더와 apply 재검증이 동일하게 호출하는 단일 출처.
     */
    static boolean isReservationStillHeld(ReservationGroup group, List<Reservation> reservations, Instant now) {
        return group.getStatus() == ReservationGroupStatus.PENDING
                && !group.isExpiredAt(now)
                && reservations.stream().allMatch(reservation -> reservation.getStatus() == ReservationStatus.PENDING)
                && reservations.stream().allMatch(reservation -> reservation.getSeat().getStatus() == SeatStatus.HELD);
    }
}
