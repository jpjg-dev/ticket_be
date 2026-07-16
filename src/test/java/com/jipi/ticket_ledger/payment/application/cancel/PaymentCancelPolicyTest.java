package com.jipi.ticket_ledger.payment.application.cancel;

import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaymentCancelPolicyTest {

    private CancelingPaymentSnapshot snapshot() {
        return new CancelingPaymentSnapshot(1L, "order-1", 10L, "pay-key-1", 11000, "KRW", 100L, false);
    }

    private PgCancelState pg(String paymentKey, String status, Integer totalAmount, Integer balanceAmount, String currency) {
        PaymentGatewayState state = switch (status) {
            case "DONE" -> PaymentGatewayState.APPROVED;
            case "CANCELED", "PARTIAL_CANCELED" -> PaymentGatewayState.CANCELED;
            case "READY", "IN_PROGRESS", "WAITING_FOR_DEPOSIT" -> PaymentGatewayState.PENDING;
            case "ABORTED", "EXPIRED" -> PaymentGatewayState.FAILED;
            default -> PaymentGatewayState.UNKNOWN;
        };
        return new PgCancelState(paymentKey, status, totalAmount, balanceAmount, currency, state);
    }

    @Test
    @DisplayName("decide: CANCELED + 응답값 일치 + 잔액 0 → FINALIZE")
    void canceledMatchFinalize() {
        CancelDecision decision = PaymentCancelPolicy.decide(
                snapshot(), pg("pay-key-1", "CANCELED", 11000, 0, "KRW"));

        assertEquals(CancelAction.FINALIZE, decision.action());
    }

    @Test
    @DisplayName("decide: PARTIAL_CANCELED 이지만 잔액 0이면 전액 환불로 확인해 FINALIZE")
    void partialCanceledWithZeroBalanceFinalize() {
        CancelDecision decision = PaymentCancelPolicy.decide(
                snapshot(), pg("pay-key-1", "PARTIAL_CANCELED", 11000, 0, "KRW"));

        assertEquals(CancelAction.FINALIZE, decision.action());
    }

    @Test
    @DisplayName("decide: PARTIAL_CANCELED + 잔액 존재 → HOLD_MANUAL")
    void partialCanceledWithRemainingBalanceHold() {
        CancelDecision decision = PaymentCancelPolicy.decide(
                snapshot(), pg("pay-key-1", "PARTIAL_CANCELED", 11000, 5000, "KRW"));

        assertEquals(CancelAction.HOLD_MANUAL, decision.action());
    }

    @Test
    @DisplayName("decide: 취소 상태라도 잔액이 불명이면 HOLD_MANUAL")
    void canceledWithUnknownBalanceHold() {
        CancelDecision decision = PaymentCancelPolicy.decide(
                snapshot(), pg("pay-key-1", "CANCELED", 11000, null, "KRW"));

        assertEquals(CancelAction.HOLD_MANUAL, decision.action());
    }

    @Test
    @DisplayName("decide: 취소 상태라도 원결제 금액이 다르면 HOLD_MANUAL")
    void canceledAmountMismatchHold() {
        CancelDecision decision = PaymentCancelPolicy.decide(
                snapshot(), pg("pay-key-1", "CANCELED", 12000, 0, "KRW"));

        assertEquals(CancelAction.HOLD_MANUAL, decision.action());
    }

    @Test
    @DisplayName("decide: CANCELED + 통화 불일치 → HOLD_MANUAL")
    void canceledCurrencyMismatchHold() {
        CancelDecision decision = PaymentCancelPolicy.decide(
                snapshot(), pg("pay-key-1", "CANCELED", 11000, 0, "USD"));

        assertEquals(CancelAction.HOLD_MANUAL, decision.action());
    }

    @Test
    @DisplayName("decide: DONE(아직 취소 안 먹음) → CANCEL_AGAIN")
    void doneCancelAgain() {
        CancelDecision decision = PaymentCancelPolicy.decide(
                snapshot(), pg("pay-key-1", "DONE", 11000, 11000, "KRW"));

        assertEquals(CancelAction.CANCEL_AGAIN, decision.action());
    }

    @Test
    @DisplayName("decide: paymentKey 불일치 → HOLD_MANUAL")
    void paymentKeyMismatchHold() {
        CancelDecision decision = PaymentCancelPolicy.decide(
                snapshot(), pg("different-key", "CANCELED", 11000, 0, "KRW"));

        assertEquals(CancelAction.HOLD_MANUAL, decision.action());
    }

    @Test
    @DisplayName("decide: 그 외 status(IN_PROGRESS 등) → HOLD_MANUAL")
    void otherStatusHold() {
        CancelDecision decision = PaymentCancelPolicy.decide(
                snapshot(), pg("pay-key-1", "IN_PROGRESS", 11000, 11000, "KRW"));

        assertEquals(CancelAction.HOLD_MANUAL, decision.action());
    }
}
