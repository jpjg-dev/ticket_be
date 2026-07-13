package com.jipi.ticket_ledger.payment.application.cancel;

import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaymentCancelPolicyTest {

    private CancelingPaymentSnapshot snapshot() {
        return new CancelingPaymentSnapshot(1L, "order-1", 10L, "pay-key-1", "KRW", 100L, false);
    }

    private PgCancelState pg(String paymentKey, String status, String currency) {
        PaymentGatewayState state = switch (status) {
            case "DONE" -> PaymentGatewayState.APPROVED;
            case "CANCELED", "PARTIAL_CANCELED" -> PaymentGatewayState.CANCELED;
            default -> PaymentGatewayState.OTHER;
        };
        return new PgCancelState(paymentKey, status, currency, state);
    }

    @Test
    @DisplayName("decide: CANCELED + paymentKey/통화 일치 → FINALIZE")
    void canceledMatchFinalize() {
        CancelDecision decision = PaymentCancelPolicy.decide(snapshot(), pg("pay-key-1", "CANCELED", "KRW"));

        assertEquals(CancelAction.FINALIZE, decision.action());
    }

    @Test
    @DisplayName("decide: PARTIAL_CANCELED + paymentKey/통화 일치 → FINALIZE")
    void partialCanceledMatchFinalize() {
        CancelDecision decision = PaymentCancelPolicy.decide(snapshot(), pg("pay-key-1", "PARTIAL_CANCELED", "KRW"));

        assertEquals(CancelAction.FINALIZE, decision.action());
    }

    @Test
    @DisplayName("decide: CANCELED + 통화 불일치 → HOLD_MANUAL")
    void canceledCurrencyMismatchHold() {
        CancelDecision decision = PaymentCancelPolicy.decide(snapshot(), pg("pay-key-1", "CANCELED", "USD"));

        assertEquals(CancelAction.HOLD_MANUAL, decision.action());
    }

    @Test
    @DisplayName("decide: DONE(아직 취소 안 먹음) → CANCEL_AGAIN")
    void doneCancelAgain() {
        CancelDecision decision = PaymentCancelPolicy.decide(snapshot(), pg("pay-key-1", "DONE", "KRW"));

        assertEquals(CancelAction.CANCEL_AGAIN, decision.action());
    }

    @Test
    @DisplayName("decide: paymentKey 불일치 → HOLD_MANUAL")
    void paymentKeyMismatchHold() {
        CancelDecision decision = PaymentCancelPolicy.decide(snapshot(), pg("different-key", "CANCELED", "KRW"));

        assertEquals(CancelAction.HOLD_MANUAL, decision.action());
    }

    @Test
    @DisplayName("decide: 그 외 status(IN_PROGRESS 등) → HOLD_MANUAL")
    void otherStatusHold() {
        CancelDecision decision = PaymentCancelPolicy.decide(snapshot(), pg("pay-key-1", "IN_PROGRESS", "KRW"));

        assertEquals(CancelAction.HOLD_MANUAL, decision.action());
    }
}
