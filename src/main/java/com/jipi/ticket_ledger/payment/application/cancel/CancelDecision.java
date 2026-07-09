package com.jipi.ticket_ledger.payment.application.cancel;

/**
 * 순수 정책(PaymentCancelPolicy.decide)의 결과. action 만 담는다(취소는 환불 사유가 없음).
 */
record CancelDecision(CancelAction action) {

    static CancelDecision finalizeCancel() {
        return new CancelDecision(CancelAction.FINALIZE);
    }

    static CancelDecision cancelAgain() {
        return new CancelDecision(CancelAction.CANCEL_AGAIN);
    }

    static CancelDecision holdManual() {
        return new CancelDecision(CancelAction.HOLD_MANUAL);
    }
}
