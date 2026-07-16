package com.jipi.ticket_ledger.payment.application.cancel;

import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayState;

import java.util.Objects;

/**
 * CANCELING 회색지대의 결정 로직. 순수 함수만 담아 트랜잭션/외부호출과 분리한다.
 */
final class PaymentCancelPolicy {

    private PaymentCancelPolicy() {
    }

    /**
     * 스냅샷과 PG 상태로 어떤 조치를 취할지 결정한다(부수효과 없음).
     * <ul>
     *   <li>취소됨 + 식별값/원결제 금액 일치 + 취소 가능 잔액 0 → FINALIZE</li>
     *   <li>전액 환불 미확정(잔액 존재) 또는 응답값 불일치/불명 → HOLD_MANUAL</li>
     *   <li>승인(DONE, 아직 취소 안 먹음) + 응답값 일치 → CANCEL_AGAIN</li>
     *   <li>그 외 status → HOLD_MANUAL</li>
     * </ul>
     * 본 시스템은 부분 취소를 지원하지 않는다. PG 상태명이 PARTIAL_CANCELED 여도 잔액이 0인 전액 환불 결과만 확정한다.
     */
    static CancelDecision decide(CancelingPaymentSnapshot snapshot, PgCancelState pgState) {
        boolean paymentKeyMatch = Objects.equals(snapshot.paymentKey(), pgState.paymentKey());
        boolean amountMatch = Objects.equals(snapshot.totalAmount(), pgState.totalAmount());
        boolean currencyMatch = Objects.equals(snapshot.currency(), pgState.currency());
        boolean responseMatch = paymentKeyMatch && amountMatch && currencyMatch;

        if (pgState.state() == PaymentGatewayState.CANCELED) {
            boolean fullyRefunded = Integer.valueOf(0).equals(pgState.balanceAmount());
            if (!responseMatch || !fullyRefunded) {
                return CancelDecision.holdManual();
            }
            return CancelDecision.finalizeCancel();
        }

        if (pgState.state() == PaymentGatewayState.APPROVED) {
            if (!responseMatch) {
                return CancelDecision.holdManual();
            }
            return CancelDecision.cancelAgain();
        }

        return CancelDecision.holdManual();
    }
}
