package com.jipi.ticket_ledger.payment.application.cancel;

import com.jipi.ticket_ledger.payment.application.recovery.PaymentRecoveryMetrics;
import com.jipi.ticket_ledger.payment.infrastructure.TossCancelResponse;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentClient;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentLookupResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentCancelServiceTest {

    private static final Long PAYMENT_ID = 1L;
    private static final Long OWNER_ID = 100L;

    @Mock
    private TossPaymentClient tossPaymentClient;

    @Mock
    private PaymentCancelTransactionService paymentCancelTransactionService;

    @Mock
    private PaymentRecoveryMetrics paymentRecoveryMetrics;

    private PaymentCancelService paymentCancelService;

    @BeforeEach
    void setUp() {
        paymentCancelService = new PaymentCancelService(
                tossPaymentClient, paymentCancelTransactionService, paymentRecoveryMetrics);
    }

    private TossPaymentLookupResponse lookup(String paymentKey, String status, String currency) {
        return new TossPaymentLookupResponse(paymentKey, "order-1", status, "CARD", 11000, currency);
    }

    private CancelingPaymentSnapshot markedSnapshot() {
        return new CancelingPaymentSnapshot(PAYMENT_ID, "order-1", 10L, "pay-key-1", "KRW", OWNER_ID, false);
    }

    @Test
    @DisplayName("cancel: PG 취소 성공(CANCELED) 이면 멱등키로 호출하고 FINALIZE 를 적용한다")
    void cancelSuccessFinalize() {
        when(paymentCancelTransactionService.markCanceling(PAYMENT_ID, OWNER_ID)).thenReturn(markedSnapshot());
        when(tossPaymentClient.cancel("pay-key-1", "사용자 요청", "KRW", "cancel:1"))
                .thenReturn(new TossCancelResponse("pay-key-1", "CANCELED", 11000, "KRW"));

        paymentCancelService.cancel(PAYMENT_ID, "사용자 요청", OWNER_ID);

        verify(tossPaymentClient).cancel("pay-key-1", "사용자 요청", "KRW", "cancel:1");
        verify(paymentCancelTransactionService).applyDecision(eq(PAYMENT_ID), any(CancelDecision.class));
    }

    @Test
    @DisplayName("cancel: PG 취소 호출 실패 + 조회 결과 CANCELED 이면 FINALIZE 를 적용한다")
    void cancelFailThenLookupCanceledFinalize() {
        when(paymentCancelTransactionService.markCanceling(PAYMENT_ID, OWNER_ID)).thenReturn(markedSnapshot());
        when(tossPaymentClient.cancel("pay-key-1", "사용자 요청", "KRW", "cancel:1"))
                .thenThrow(new ResourceAccessException("boom"));
        when(tossPaymentClient.getPaymentByPaymentKey("pay-key-1"))
                .thenReturn(new TossPaymentLookupResponse("pay-key-1", "order-1", "CANCELED", "CARD", 11000, "KRW"));

        paymentCancelService.cancel(PAYMENT_ID, "사용자 요청", OWNER_ID);

        verify(paymentCancelTransactionService).applyDecision(eq(PAYMENT_ID), any(CancelDecision.class));
    }

    @Test
    @DisplayName("cancel: PG 취소 호출 실패 + 조회 결과 DONE(아직 취소 안 먹음) 이면 CANCELING 유지, 무쓰기")
    void cancelFailThenLookupDoneKeepsCanceling() {
        when(paymentCancelTransactionService.markCanceling(PAYMENT_ID, OWNER_ID)).thenReturn(markedSnapshot());
        when(tossPaymentClient.cancel("pay-key-1", "사용자 요청", "KRW", "cancel:1"))
                .thenThrow(new ResourceAccessException("boom"));
        when(tossPaymentClient.getPaymentByPaymentKey("pay-key-1"))
                .thenReturn(new TossPaymentLookupResponse("pay-key-1", "order-1", "DONE", "CARD", 11000, "KRW"));

        paymentCancelService.cancel(PAYMENT_ID, "사용자 요청", OWNER_ID);

        verify(paymentCancelTransactionService, never()).applyDecision(any(), any());
    }

    @Test
    @DisplayName("cancel: PG 취소 timeout + 조회까지 실패면 CANCELING 유지, 예외를 삼킨다")
    void cancelTimeoutAndLookupFailKeepsCanceling() {
        when(paymentCancelTransactionService.markCanceling(PAYMENT_ID, OWNER_ID)).thenReturn(markedSnapshot());
        when(tossPaymentClient.cancel("pay-key-1", "사용자 요청", "KRW", "cancel:1"))
                .thenThrow(new ResourceAccessException("timeout"));
        when(tossPaymentClient.getPaymentByPaymentKey("pay-key-1"))
                .thenThrow(new ResourceAccessException("timeout"));

        paymentCancelService.cancel(PAYMENT_ID, "사용자 요청", OWNER_ID);

        verify(paymentCancelTransactionService, never()).applyDecision(any(), any());
    }

    @Test
    @DisplayName("cancel: markCanceling 이 이미 CANCELED(멱등)면 외부 호출 없이 즉시 종료한다")
    void cancelAlreadyCanceledIdempotent() {
        when(paymentCancelTransactionService.markCanceling(PAYMENT_ID, OWNER_ID))
                .thenReturn(CancelingPaymentSnapshot.alreadyCanceled(PAYMENT_ID, "order-1", 10L));

        paymentCancelService.cancel(PAYMENT_ID, "사용자 요청", OWNER_ID);

        verifyNoInteractions(tossPaymentClient);
        verify(paymentCancelTransactionService, never()).applyDecision(any(), any());
    }

    @Test
    @DisplayName("cancel: 소유자 검증 실패로 markCanceling 이 예외를 던지면 외부 호출/적용 없이 전파한다")
    void cancelForbiddenPropagates() {
        when(paymentCancelTransactionService.markCanceling(PAYMENT_ID, 999L))
                .thenThrow(new IllegalStateException("잘못된 접근 입니다."));

        assertThrows(IllegalStateException.class,
                () -> paymentCancelService.cancel(PAYMENT_ID, "사용자 요청", 999L));

        verifyNoInteractions(tossPaymentClient);
        verify(paymentCancelTransactionService, never()).applyDecision(any(), any());
    }

    @Test
    @DisplayName("recoverCanceling: 조회 결과 CANCELED 면 재취소 없이 FINALIZE 를 적용한다")
    void recoverCancelingLookupCanceledFinalize() {
        when(paymentCancelTransactionService.loadCancelingSnapshot(PAYMENT_ID)).thenReturn(markedSnapshot());
        when(tossPaymentClient.getPaymentByPaymentKey("pay-key-1"))
                .thenReturn(lookup("pay-key-1", "CANCELED", "KRW"));

        CancelOutcome outcome = paymentCancelService.recoverCanceling(PAYMENT_ID);

        assertEquals(CancelOutcome.CANCELED, outcome);
        verify(tossPaymentClient, never()).cancel(anyString(), anyString(), anyString(), anyString());
        verify(paymentCancelTransactionService).applyDecision(eq(PAYMENT_ID), any(CancelDecision.class));
        verify(paymentRecoveryMetrics).record(CancelOutcome.CANCELED);
    }

    @Test
    @DisplayName("recoverCanceling: 조회 DONE 이면 같은 멱등키로 재취소하고 성공(CANCELED)이면 FINALIZE 를 적용한다")
    void recoverCancelingLookupDoneReCancelFinalize() {
        when(paymentCancelTransactionService.loadCancelingSnapshot(PAYMENT_ID)).thenReturn(markedSnapshot());
        when(tossPaymentClient.getPaymentByPaymentKey("pay-key-1"))
                .thenReturn(lookup("pay-key-1", "DONE", "KRW"));
        when(tossPaymentClient.cancel("pay-key-1", "CANCEL_RECOVERY", "KRW", "cancel:1"))
                .thenReturn(new TossCancelResponse("pay-key-1", "CANCELED", 11000, "KRW"));

        CancelOutcome outcome = paymentCancelService.recoverCanceling(PAYMENT_ID);

        assertEquals(CancelOutcome.CANCELED, outcome);
        verify(tossPaymentClient).cancel("pay-key-1", "CANCEL_RECOVERY", "KRW", "cancel:1");
        verify(paymentCancelTransactionService).applyDecision(eq(PAYMENT_ID), any(CancelDecision.class));
        verify(paymentRecoveryMetrics).record(CancelOutcome.CANCELED);
    }

    @Test
    @DisplayName("recoverCanceling: 조회 DONE → 재취소 후에도 여전히 DONE 이면 CANCELING 유지, 무쓰기(KEEP_CANCELING)")
    void recoverCancelingReCancelStillDoneKeepsCanceling() {
        when(paymentCancelTransactionService.loadCancelingSnapshot(PAYMENT_ID)).thenReturn(markedSnapshot());
        when(tossPaymentClient.getPaymentByPaymentKey("pay-key-1"))
                .thenReturn(lookup("pay-key-1", "DONE", "KRW"));
        when(tossPaymentClient.cancel("pay-key-1", "CANCEL_RECOVERY", "KRW", "cancel:1"))
                .thenReturn(new TossCancelResponse("pay-key-1", "DONE", 11000, "KRW"));

        CancelOutcome outcome = paymentCancelService.recoverCanceling(PAYMENT_ID);

        assertEquals(CancelOutcome.KEEP_CANCELING, outcome);
        verify(paymentCancelTransactionService, never()).applyDecision(any(), any());
        verify(paymentRecoveryMetrics).record(CancelOutcome.KEEP_CANCELING);
    }

    @Test
    @DisplayName("recoverCanceling: 재취소 호출 실패 + 폴백 조회도 실패면 무쓰기 CANCELING 유지(CANCEL_UNRESOLVED)")
    void recoverCancelingReCancelFailsAndLookupFailsKeepsCanceling() {
        when(paymentCancelTransactionService.loadCancelingSnapshot(PAYMENT_ID)).thenReturn(markedSnapshot());
        when(tossPaymentClient.getPaymentByPaymentKey("pay-key-1"))
                .thenReturn(lookup("pay-key-1", "DONE", "KRW"))
                .thenThrow(new ResourceAccessException("timeout"));
        when(tossPaymentClient.cancel("pay-key-1", "CANCEL_RECOVERY", "KRW", "cancel:1"))
                .thenThrow(new ResourceAccessException("timeout"));

        CancelOutcome outcome = paymentCancelService.recoverCanceling(PAYMENT_ID);

        assertEquals(CancelOutcome.CANCEL_UNRESOLVED, outcome);
        verify(paymentCancelTransactionService, never()).applyDecision(any(), any());
        verify(paymentRecoveryMetrics).record(CancelOutcome.CANCEL_UNRESOLVED);
    }

    @Test
    @DisplayName("recoverCanceling: 조회 paymentKey 불일치면 HOLD_MANUAL, 재취소/적용 없이 CANCELING 유지")
    void recoverCancelingPaymentKeyMismatchHoldManual() {
        when(paymentCancelTransactionService.loadCancelingSnapshot(PAYMENT_ID)).thenReturn(markedSnapshot());
        when(tossPaymentClient.getPaymentByPaymentKey("pay-key-1"))
                .thenReturn(lookup("different-pay-key", "DONE", "KRW"));

        CancelOutcome outcome = paymentCancelService.recoverCanceling(PAYMENT_ID);

        assertEquals(CancelOutcome.HELD_MANUAL, outcome);
        verify(tossPaymentClient, never()).cancel(anyString(), anyString(), anyString(), anyString());
        verify(paymentCancelTransactionService, never()).applyDecision(any(), any());
        verify(paymentRecoveryMetrics).record(CancelOutcome.HELD_MANUAL);
    }

    @Test
    @DisplayName("recoverCanceling: 조회가 RestClientException 이면 무쓰기 CANCELING 유지(LOOKUP_UNRESOLVED)")
    void recoverCancelingLookupFailsKeepsCanceling() {
        when(paymentCancelTransactionService.loadCancelingSnapshot(PAYMENT_ID)).thenReturn(markedSnapshot());
        when(tossPaymentClient.getPaymentByPaymentKey("pay-key-1"))
                .thenThrow(new ResourceAccessException("timeout"));

        CancelOutcome outcome = paymentCancelService.recoverCanceling(PAYMENT_ID);

        assertEquals(CancelOutcome.LOOKUP_UNRESOLVED, outcome);
        verify(tossPaymentClient, never()).cancel(anyString(), anyString(), anyString(), anyString());
        verify(paymentCancelTransactionService, never()).applyDecision(any(), any());
        verify(paymentRecoveryMetrics).record(CancelOutcome.LOOKUP_UNRESOLVED);
    }

    @Test
    @DisplayName("recoverCanceling: 스냅샷이 null(이미 해소)이면 아무것도 하지 않는다(NOOP)")
    void recoverCancelingSnapshotNullNoop() {
        when(paymentCancelTransactionService.loadCancelingSnapshot(PAYMENT_ID)).thenReturn(null);

        CancelOutcome outcome = paymentCancelService.recoverCanceling(PAYMENT_ID);

        assertEquals(CancelOutcome.NOOP_NOT_CANCELING, outcome);
        verifyNoInteractions(tossPaymentClient);
        verify(paymentCancelTransactionService, never()).applyDecision(any(), any());
        verify(paymentRecoveryMetrics).record(CancelOutcome.NOOP_NOT_CANCELING);
    }
}
