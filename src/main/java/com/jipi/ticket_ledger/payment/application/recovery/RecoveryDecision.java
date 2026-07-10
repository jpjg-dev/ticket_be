package com.jipi.ticket_ledger.payment.application.recovery;

/**
 * 순수 정책(RecoveryPolicy.decide)의 결과. action 과 (환불이 필요한 경우) 환불 사유를 담는다.
 */
record RecoveryDecision(RecoveryAction action, String refundReason) {

    static RecoveryDecision approve() {
        return new RecoveryDecision(RecoveryAction.APPROVE, null);
    }

    static RecoveryDecision fail() {
        return new RecoveryDecision(RecoveryAction.FAIL, null);
    }

    static RecoveryDecision refundThenFail(String reason) {
        return new RecoveryDecision(RecoveryAction.REFUND_THEN_FAIL, reason);
    }

    static RecoveryDecision holdManual() {
        return new RecoveryDecision(RecoveryAction.HOLD_MANUAL, null);
    }
}
