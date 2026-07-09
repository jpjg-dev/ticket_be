package com.jipi.ticket_ledger.payment.application.recovery;

import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentLookupResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * 보정의 DB 트랜잭션 경계만 담당한다. 외부 PG 호출(환불 등)은 이 계층에 두지 않는다.
 * (1) readonly 스냅샷 로드, (4) 락 하 결정 적용의 두 트랜잭션으로 분리한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentRecoveryTransactionService {

    private final PaymentRepository paymentRepository;
    private final ReservationRepository reservationRepository;
    private final Clock clock;

    /**
     * (1) readonly 트랜잭션에서 CONFIRMING 결제의 원시 스냅샷을 만든다.
     * CONFIRMING 이 아니면 null. 엔티티/컬렉션은 트랜잭션 밖으로 흘리지 않는다.
     */
    @Transactional(readOnly = true)
    public RecoverySnapshot loadRecoverySnapshot(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.CONFIRMING) {
            return null;
        }

        List<Reservation> reservations =
                reservationRepository.findByReservationGroupIdWithSeat(payment.getReservationGroup().getId());
        boolean reservationHeld =
                RecoveryPolicy.isReservationStillHeld(payment.getReservationGroup(), reservations, clock.instant());

        return new RecoverySnapshot(
                payment.getId(),
                payment.getOrderId(),
                payment.totalAmountWithVat(),
                payment.getCurrency(),
                reservationHeld
        );
    }

    /**
     * (4) write 트랜잭션에서 행 락을 다시 잡고 결정을 적용한다.
     * 환불 호출은 이미 외부에서 끝난 상태로 진입한다(REFUND_THEN_FAIL 은 여기서 실패 처리만).
     */
    @Transactional
    public RecoveryOutcome applyDecision(Long paymentId, RecoveryDecision decision, TossPaymentLookupResponse lookup) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.CONFIRMING) {
            return RecoveryOutcome.NOOP_NOT_CONFIRMING;
        }

        List<Reservation> reservations =
                reservationRepository.findByReservationGroupIdWithSeat(payment.getReservationGroup().getId());

        switch (decision.action()) {
            case APPROVE -> {
                // 스냅샷 시점 이후 좌석이 유실됐을 수 있으므로 락 하에서 다시 검증한다.
                if (!RecoveryPolicy.isReservationStillHeld(payment.getReservationGroup(), reservations, clock.instant())) {
                    log.warn("Seat lost before approval apply, deferring to next cycle. paymentId={} orderId={}",
                            payment.getId(), payment.getOrderId());
                    return RecoveryOutcome.SEAT_LOST_DEFERRED;
                }
                applyApproval(payment, reservations, lookup.paymentKey(), lookup.method(), lookup.status());
                log.info("Recovered CONFIRMING payment. paymentId={} orderId={} pgStatus={}",
                        payment.getId(), payment.getOrderId(), lookup.status());
                return RecoveryOutcome.APPROVED;
            }
            case FAIL -> {
                failAndRelease(payment, reservations);
                log.warn("Failed CONFIRMING payment after PG lookup. paymentId={} orderId={} pgStatus={}",
                        payment.getId(), payment.getOrderId(), lookup.status());
                return RecoveryOutcome.FAILED_RELEASED;
            }
            case REFUND_THEN_FAIL -> {
                failAndRelease(payment, reservations);
                log.warn("Refunded CONFIRMING payment then failed. paymentId={} orderId={} reason={} pgStatus={}",
                        payment.getId(), payment.getOrderId(), decision.refundReason(), lookup.status());
                return RecoveryOutcome.REFUNDED_FAILED;
            }
            default -> {
                // HOLD_MANUAL 은 오케스트레이터가 applyDecision 을 호출하지 않는다. 방어적으로 무쓰기 반환.
                return RecoveryOutcome.HELD_MANUAL;
            }
        }
    }

    private void applyApproval(Payment payment, List<Reservation> reservations, String paymentKey, String method, String pgStatus) {
        payment.approve(paymentKey, method, pgStatus);
        payment.getReservationGroup().confirm();
        reservations.forEach(reservation -> {
            reservation.confirm();
            reservation.getSeat().book();
        });
    }

    private void failAndRelease(Payment payment, List<Reservation> reservations) {
        payment.fail();
        payment.getReservationGroup().expire();
        reservations.forEach(reservation -> {
            reservation.expire();
            reservation.getSeat().release();
        });
    }
}
