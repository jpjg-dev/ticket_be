package com.jipi.ticket_ledger.payment.application.cancel;

import com.jipi.ticket_ledger.global.exception.ForbiddenAccessException;
import com.jipi.ticket_ledger.global.log.LogEvents;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.payment.infrastructure.PaymentLogFormatter;
import com.jipi.ticket_ledger.reservation.domain.Reservation;
import com.jipi.ticket_ledger.reservation.domain.ReservationRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * 취소의 DB 트랜잭션 경계만 담당한다. 외부 PG 호출은 이 계층에 두지 않는다.
 * (1) markCanceling: 소유자 검증 + APPROVED -> CANCELING durable 마커 커밋,
 * (2) applyDecision: 행 락을 다시 잡고 CANCELING 을 재확인한 뒤 결정을 적용하는 두 트랜잭션으로 분리한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentCancelTransactionService {

    private final PaymentRepository paymentRepository;
    private final ReservationRepository reservationRepository;
    private final Clock clock;

    /**
     * (1) write 트랜잭션에서 행 락을 잡고 소유자 검증 후 APPROVED -> CANCELING durable 마커를 남긴다.
     * <ul>
     *   <li>소유자 불일치 → throw(무쓰기, {@link ForbiddenAccessException} → 403)</li>
     *   <li>CANCELED → 멱등 종료 신호 스냅샷 반환(외부 호출 불필요)</li>
     *   <li>paymentKey blank → throw</li>
     *   <li>APPROVED → startCanceling 후 스냅샷 반환</li>
     *   <li>CANCELING(재진입) → 마킹 없이 스냅샷 반환</li>
     *   <li>그 외(READY/FAILED 등) → throw</li>
     * </ul>
     * 이 메서드가 스냅샷(비 멱등)을 반환하면 결제는 최소 CANCELING durable 상태가 보장된다.
     */
    @Transactional
    public CancelingPaymentSnapshot markCanceling(Long paymentId, Long requesterUserId) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("결제를 찾을 수 없습니다."));

        Long reservationGroupId = payment.getReservationGroup().getId();
        Long ownerUserId = payment.getReservationGroup().getUser().getId();

        verifyOwner(payment, requesterUserId, reservationGroupId, ownerUserId);

        log.info("event={} orderId={} paymentId={} reservationGroupId={} reason={} paymentKeyMasked={}",
                LogEvents.PAYMENT_CANCEL_START, payment.getOrderId(), payment.getId(), reservationGroupId,
                "REQUEST", PaymentLogFormatter.maskPaymentKey(payment.getPaymentKey()));

        if (payment.getStatus() == PaymentStatus.CANCELED) {
            log.info("event={} orderId={} paymentId={} reservationGroupId={} reason={} pgStatus={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CANCEL_SUCCESS, payment.getOrderId(), payment.getId(), reservationGroupId,
                    "ALREADY_CANCELED", payment.getPgStatus(), PaymentLogFormatter.maskPaymentKey(payment.getPaymentKey()));
            return CancelingPaymentSnapshot.alreadyCanceled(payment.getId(), payment.getOrderId(), reservationGroupId);
        }

        if (payment.getPaymentKey() == null || payment.getPaymentKey().isBlank()) {
            log.warn("event={} orderId={} paymentId={} reservationGroupId={} reason={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CANCEL_REJECT, payment.getOrderId(), payment.getId(), reservationGroupId,
                    "MISSING_PAYMENT_KEY", PaymentLogFormatter.maskPaymentKey(payment.getPaymentKey()));
            throw new IllegalStateException("PG 결제키가 없어 취소할 수 없습니다.");
        }

        if (payment.getStatus() == PaymentStatus.APPROVED) {
            payment.startCanceling(clock.instant());
            return toSnapshot(payment, reservationGroupId, ownerUserId);
        }

        if (payment.getStatus() == PaymentStatus.CANCELING) {
            // 재진입: 이미 durable 마커가 있으므로 추가 마킹 없이 스냅샷만 반환한다.
            return toSnapshot(payment, reservationGroupId, ownerUserId);
        }

        log.warn("event={} orderId={} paymentId={} reservationGroupId={} reason={} paymentKeyMasked={}",
                LogEvents.PAYMENT_CANCEL_REJECT, payment.getOrderId(), payment.getId(), reservationGroupId,
                "INVALID_PAYMENT_STATUS", PaymentLogFormatter.maskPaymentKey(payment.getPaymentKey()));
        throw new IllegalStateException("승인된 결제만 취소할 수 있습니다.");
    }

    /**
     * (2) write 트랜잭션에서 행 락을 다시 잡고 CANCELING 을 재확인한 뒤 결정을 적용한다.
     * <ul>
     *   <li>CANCELING 이 아니면 no-op(멱등) — 이미 취소 완료됐거나 상태가 바뀐 경우</li>
     *   <li>FINALIZE → payment.cancel + group.cancel + reservation.cancel + seat.releaseBooked</li>
     *   <li>HOLD_MANUAL → 무쓰기 error 로그</li>
     * </ul>
     * CANCEL_AGAIN 은 오케스트레이터가 처리하며 이 메서드로 오지 않는다.
     */
    @Transactional
    public void applyDecision(Long paymentId, CancelDecision decision) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("결제를 찾을 수 없습니다."));

        if (payment.getStatus() != PaymentStatus.CANCELING) {
            // 재락 시점에 이미 취소 완료됐거나 상태가 바뀜 → 멱등 no-op.
            return;
        }

        Long reservationGroupId = payment.getReservationGroup().getId();

        switch (decision.action()) {
            case FINALIZE -> {
                List<Reservation> reservations = getReservationsForPayment(payment);
                applyCancellation(payment, reservations);
                log.info("event={} orderId={} paymentId={} reservationGroupId={} reason={} pgStatus={} paymentKeyMasked={}",
                        LogEvents.PAYMENT_CANCEL_SUCCESS, payment.getOrderId(), payment.getId(), reservationGroupId,
                        "FINALIZE", payment.getPgStatus(), PaymentLogFormatter.maskPaymentKey(payment.getPaymentKey()));
            }
            case HOLD_MANUAL -> log.error("event={} orderId={} paymentId={} reservationGroupId={} reason={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CANCEL_REJECT, payment.getOrderId(), payment.getId(), reservationGroupId,
                    "HOLD_MANUAL", PaymentLogFormatter.maskPaymentKey(payment.getPaymentKey()));
            case CANCEL_AGAIN -> {
                // 오케스트레이터가 처리(CANCELING 유지). 방어적으로 무쓰기.
            }
        }
    }

    private void verifyOwner(Payment payment, Long requesterUserId, Long reservationGroupId, Long ownerUserId) {
        if (ownerUserId == null || !Objects.equals(ownerUserId, requesterUserId)) {
            log.warn("event={} orderId={} paymentId={} reservationGroupId={} requesterUserId={} reason={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CANCEL_REJECT, payment.getOrderId(), payment.getId(), reservationGroupId,
                    requesterUserId, "FORBIDDEN_CANCEL_ACCESS", PaymentLogFormatter.maskPaymentKey(payment.getPaymentKey()));
            throw new ForbiddenAccessException("잘못된 접근 입니다.");
        }
    }

    private void applyCancellation(Payment payment, List<Reservation> reservations) {
        payment.cancel(clock.instant());
        payment.getReservationGroup().cancel();
        reservations.forEach(reservation -> {
            reservation.cancel();
            reservation.getSeat().releaseBooked();
        });
    }

    private CancelingPaymentSnapshot toSnapshot(Payment payment, Long reservationGroupId, Long ownerUserId) {
        return new CancelingPaymentSnapshot(
                payment.getId(),
                payment.getOrderId(),
                reservationGroupId,
                payment.getPaymentKey(),
                payment.getCurrency(),
                ownerUserId,
                false
        );
    }

    private List<Reservation> getReservationsForPayment(Payment payment) {
        List<Reservation> reservations = reservationRepository.findByReservationGroupId(payment.getReservationGroup().getId());
        if (reservations.isEmpty()) {
            throw new EntityNotFoundException("예매를 찾을 수 없습니다.");
        }
        return reservations;
    }
}
