package com.jipi.ticket_ledger.payment.application.cancel;

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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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

    private PaymentCancelService paymentCancelService;

    @BeforeEach
    void setUp() {
        paymentCancelService = new PaymentCancelService(tossPaymentClient, paymentCancelTransactionService);
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
}
