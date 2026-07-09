package com.jipi.ticket_ledger.payment.application.cancel;

import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentStatus;

/**
 * CANCELING 회색지대의 결정 로직. 순수 함수만 담아 트랜잭션/외부호출과 분리한다.
 */
final class PaymentCancelPolicy {

    private PaymentCancelPolicy() {
    }

    /**
     * 스냅샷과 PG 상태로 어떤 조치를 취할지 결정한다(부수효과 없음).
     * <ul>
     *   <li>취소됨(CANCELED/PARTIAL_CANCELED) + paymentKey 일치 + 통화 일치 → FINALIZE</li>
     *   <li>취소됨 + paymentKey 불일치 → HOLD_MANUAL(다른 결제건 의심)</li>
     *   <li>취소됨 + 통화 불일치 → HOLD_MANUAL</li>
     *   <li>승인(DONE, 아직 취소 안 먹음) + paymentKey 일치 → CANCEL_AGAIN</li>
     *   <li>승인 + paymentKey 불일치 → HOLD_MANUAL</li>
     *   <li>그 외 status → HOLD_MANUAL</li>
     * </ul>
     * 전액취소 전제: {@code isCanceled} 는 PARTIAL_CANCELED 를 포함하나, 본 시스템은 취소를 항상 전액으로만 발행한다.
     * PARTIAL_CANCELED 가 관측되면 운영상 예외 신호이며, 여기서는 FINALIZE(전량 좌석 release) 로 수렴한다.
     */
    static CancelDecision decide(CancelingPaymentSnapshot snapshot, PgCancelState pgState) {
        boolean paymentKeyMatch = snapshot.paymentKey().equals(pgState.paymentKey());
        boolean currencyMatch = snapshot.currency().equals(pgState.currency());

        if (TossPaymentStatus.isCanceled(pgState.status())) {
            if (!paymentKeyMatch || !currencyMatch) {
                return CancelDecision.holdManual();
            }
            return CancelDecision.finalizeCancel();
        }

        if (TossPaymentStatus.isApproved(pgState.status())) {
            if (!paymentKeyMatch) {
                return CancelDecision.holdManual();
            }
            return CancelDecision.cancelAgain();
        }

        return CancelDecision.holdManual();
    }
}
