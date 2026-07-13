package com.jipi.ticket_ledger.payment.application.recovery;

import com.jipi.ticket_ledger.payment.application.observability.PaymentRecoveryMetrics;
import com.jipi.ticket_ledger.payment.application.cancel.CancelOutcome;
import com.jipi.ticket_ledger.payment.application.cancel.PaymentCancelService;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGateway;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayCircuitState;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentLookupResponse;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentRecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Long PAYMENT_ID = 10L;

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentGateway paymentGateway = mock(PaymentGateway.class);
    private final PaymentGatewayCircuitState paymentGatewayCircuitState = mock(PaymentGatewayCircuitState.class);
    private final PaymentRecoveryTransactionService transactionService = mock(PaymentRecoveryTransactionService.class);
    private final PaymentCancelService paymentCancelService = mock(PaymentCancelService.class);
    private final PaymentRecoveryMetrics metrics = mock(PaymentRecoveryMetrics.class);
    private final PaymentRecoveryService service = new PaymentRecoveryService(
            paymentRepository, paymentGateway, paymentGatewayCircuitState, transactionService, paymentCancelService, metrics,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private RecoverySnapshot snapshot(boolean held) {
        return new RecoverySnapshot(PAYMENT_ID, "order-1", 11000, "KRW", held);
    }

    private TossPaymentLookupResponse lookup(String status, String orderId) {
        return new TossPaymentLookupResponse("pay-key-1", orderId, status, "CARD", 11000, "KRW");
    }

    @Test
    @DisplayName("recover REFUND_THEN_FAIL: 멱등키로 cancel 호출 후 applyDecision 을 호출한다")
    void recoverRefundThenApply() {
        TossPaymentLookupResponse lookup = lookup("DONE", "order-1");
        when(transactionService.loadRecoverySnapshot(PAYMENT_ID)).thenReturn(snapshot(false));
        when(paymentGateway.getPaymentByOrderId("order-1")).thenReturn(lookup);
        when(transactionService.applyDecision(eq(PAYMENT_ID), any(), eq(lookup)))
                .thenReturn(RecoveryOutcome.REFUNDED_FAILED);

        RecoveryOutcome outcome = service.recover(PAYMENT_ID);

        assertEquals(RecoveryOutcome.REFUNDED_FAILED, outcome);
        verify(paymentGateway).cancel("pay-key-1", "SEAT_UNAVAILABLE", "KRW", "cancel:10");
        verify(transactionService).applyDecision(
                eq(PAYMENT_ID), eq(RecoveryDecision.refundThenFail("SEAT_UNAVAILABLE")), eq(lookup));
        verify(metrics).record(RecoveryOutcome.REFUNDED_FAILED);
    }

    @Test
    @DisplayName("recover REFUND_THEN_FAIL: cancel 실패 시 applyDecision 미호출 + REFUND_PENDING + 환불실패 기록")
    void recoverRefundFailsKeepsConfirming() {
        when(transactionService.loadRecoverySnapshot(PAYMENT_ID)).thenReturn(snapshot(false));
        when(paymentGateway.getPaymentByOrderId("order-1")).thenReturn(lookup("DONE", "order-1"));
        when(paymentGateway.cancel(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new PaymentGatewayException("timeout"));

        RecoveryOutcome outcome = service.recover(PAYMENT_ID);

        assertEquals(RecoveryOutcome.REFUND_PENDING, outcome);
        verify(transactionService, never()).applyDecision(any(), any(), any());
        verify(metrics).record(RecoveryOutcome.REFUND_PENDING);
    }

    @Test
    @DisplayName("recover APPROVE: cancel 을 호출하지 않고 applyDecision 만 호출한다")
    void recoverApproveNoCancel() {
        TossPaymentLookupResponse lookup = lookup("DONE", "order-1");
        when(transactionService.loadRecoverySnapshot(PAYMENT_ID)).thenReturn(snapshot(true));
        when(paymentGateway.getPaymentByOrderId("order-1")).thenReturn(lookup);
        when(transactionService.applyDecision(eq(PAYMENT_ID), any(), eq(lookup)))
                .thenReturn(RecoveryOutcome.APPROVED);

        RecoveryOutcome outcome = service.recover(PAYMENT_ID);

        assertEquals(RecoveryOutcome.APPROVED, outcome);
        verify(paymentGateway, never()).cancel(anyString(), anyString(), anyString(), anyString());
        verify(transactionService).applyDecision(eq(PAYMENT_ID), eq(RecoveryDecision.approve()), eq(lookup));
    }

    @Test
    @DisplayName("recover HOLD_MANUAL: cancel/applyDecision 미호출, 로그와 메트릭만 남긴다")
    void recoverHoldManual() {
        when(transactionService.loadRecoverySnapshot(PAYMENT_ID)).thenReturn(snapshot(true));
        when(paymentGateway.getPaymentByOrderId("order-1")).thenReturn(lookup("DONE", "other-order"));

        RecoveryOutcome outcome = service.recover(PAYMENT_ID);

        assertEquals(RecoveryOutcome.HELD_MANUAL, outcome);
        verify(paymentGateway, never()).cancel(anyString(), anyString(), anyString(), anyString());
        verify(transactionService, never()).applyDecision(any(), any(), any());
        verify(metrics).record(RecoveryOutcome.HELD_MANUAL);
    }

    @Test
    @DisplayName("recover R3 자가치유: 환불 성공 후 크래시로 CONFIRMING 잔존 → 2차 조회가 CANCELED면 재환불 없이 FAIL")
    void recoverSelfHealsAfterRefundCrash() {
        TossPaymentLookupResponse canceledLookup = lookup("CANCELED", "order-1");
        when(transactionService.loadRecoverySnapshot(PAYMENT_ID)).thenReturn(snapshot(false));
        when(paymentGateway.getPaymentByOrderId("order-1")).thenReturn(canceledLookup);
        when(transactionService.applyDecision(eq(PAYMENT_ID), any(), eq(canceledLookup)))
                .thenReturn(RecoveryOutcome.FAILED_RELEASED);

        RecoveryOutcome outcome = service.recover(PAYMENT_ID);

        assertEquals(RecoveryOutcome.FAILED_RELEASED, outcome);
        verify(paymentGateway, never()).cancel(anyString(), anyString(), anyString(), anyString());
        verify(transactionService).applyDecision(eq(PAYMENT_ID), eq(RecoveryDecision.fail()), eq(canceledLookup));
    }

    @Test
    @DisplayName("배치: recover 중 조회가 RestClientException → LOOKUP_UNRESOLVED 로 실패 집계, 배치 중단 없음")
    void batchLookupFailsCountsFailed() {
        when(paymentRepository.findStaleConfirmingIds(any(), any())).thenReturn(List.of(PAYMENT_ID));
        when(transactionService.loadRecoverySnapshot(PAYMENT_ID)).thenReturn(snapshot(true));
        when(paymentGateway.getPaymentByOrderId("order-1")).thenThrow(new PaymentGatewayException("timeout"));

        int recovered = service.reconcileStaleConfirmingPayments(Duration.ZERO, 20);

        assertEquals(0, recovered);
        verify(metrics).record(RecoveryOutcome.LOOKUP_UNRESOLVED);
    }

    @Test
    @DisplayName("배치: recover 가 예상 못 한 예외를 던지면 배치가 격리하고 배치 예외 카운터를 올린다")
    void batchIsolatesUnexpectedException() {
        when(paymentRepository.findStaleConfirmingIds(any(), any())).thenReturn(List.of(PAYMENT_ID));
        when(transactionService.loadRecoverySnapshot(PAYMENT_ID)).thenThrow(new RuntimeException("boom"));

        int recovered = service.reconcileStaleConfirmingPayments(Duration.ZERO, 20);

        assertEquals(0, recovered);
        verify(metrics).recordBatchException("confirm");
    }

    @Test
    @DisplayName("cancel 배치: recoverCanceling 위임 + per-item 예외격리(한 건 throw 해도 나머지 진행 + failed 카운터)")
    void cancelBatchDelegatesAndIsolatesFailure() {
        when(paymentRepository.findStaleCancelingIds(any(), any())).thenReturn(List.of(1L, 2L));
        when(paymentCancelService.recoverCanceling(1L)).thenThrow(new RuntimeException("boom"));
        when(paymentCancelService.recoverCanceling(2L)).thenReturn(CancelOutcome.CANCELED);

        int recovered = service.reconcileStaleCancelingPayments(Duration.ZERO, 20);

        assertEquals(1, recovered);
        verify(paymentCancelService).recoverCanceling(1L);
        verify(paymentCancelService).recoverCanceling(2L);
        verify(metrics).recordBatchException("cancel");
    }

    @Test
    @DisplayName("배치 중 lookup breaker가 OPEN으로 바뀌면 남은 결제는 호출하지 않는다")
    void batchStopsRemainingPaymentsWhenLookupCircuitOpens() {
        when(paymentRepository.findStaleConfirmingIds(any(), any())).thenReturn(List.of(1L, 2L, 3L));
        when(paymentGatewayCircuitState.isLookupCircuitOpen()).thenReturn(false, true);
        when(transactionService.loadRecoverySnapshot(1L)).thenThrow(new RuntimeException("opens circuit"));

        int recovered = service.reconcileStaleConfirmingPayments(Duration.ZERO, 20);

        assertEquals(0, recovered);
        verify(transactionService).loadRecoverySnapshot(1L);
        verify(transactionService, never()).loadRecoverySnapshot(2L);
        verify(transactionService, never()).loadRecoverySnapshot(3L);
    }

    @Test
    @DisplayName("updateBacklogGauges: CONFIRMING/CANCELING countByStatus 를 metric 에 반영한다")
    void updateBacklogGaugesReflectsCounts() {
        when(paymentRepository.countByStatus(com.jipi.ticket_ledger.payment.domain.PaymentStatus.CONFIRMING)).thenReturn(3L);
        when(paymentRepository.countByStatus(com.jipi.ticket_ledger.payment.domain.PaymentStatus.CANCELING)).thenReturn(5L);

        service.updateBacklogGauges();

        verify(metrics).updateBacklog(3L, 5L);
    }

    @Test
    @DisplayName("동기경로: 조회 RestClientException → handled=true (CONFIRMING 유지, 스케줄러 위임)")
    void syncLookupFailsHandledTrue() {
        Payment payment = confirmingPayment("order-1");
        when(paymentRepository.findByOrderId("order-1")).thenReturn(Optional.of(payment));
        when(transactionService.loadRecoverySnapshot(PAYMENT_ID)).thenReturn(snapshot(true));
        when(paymentGateway.getPaymentByOrderId("order-1")).thenThrow(new PaymentGatewayException("timeout"));

        PaymentRecoveryService.SyncReconcileResult result =
                service.reconcileConfirmingPaymentByOrderId("order-1");

        assertTrue(result.handled());
    }

    @Test
    @DisplayName("동기경로: 환불 RestClientException → handled=true")
    void syncRefundFailsHandledTrue() {
        Payment payment = confirmingPayment("order-1");
        when(paymentRepository.findByOrderId("order-1")).thenReturn(Optional.of(payment));
        when(transactionService.loadRecoverySnapshot(PAYMENT_ID)).thenReturn(snapshot(false));
        when(paymentGateway.getPaymentByOrderId("order-1")).thenReturn(lookup("DONE", "order-1"));
        when(paymentGateway.cancel(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new PaymentGatewayException("timeout"));

        PaymentRecoveryService.SyncReconcileResult result =
                service.reconcileConfirmingPaymentByOrderId("order-1");

        assertTrue(result.handled());
    }

    @Test
    @DisplayName("동기경로: CONFIRMING 아님 → handled=false, PG 조회하지 않음")
    void syncNotConfirmingHandledFalse() {
        Payment payment = approvedPayment("order-1");
        when(paymentRepository.findByOrderId("order-1")).thenReturn(Optional.of(payment));

        PaymentRecoveryService.SyncReconcileResult result =
                service.reconcileConfirmingPaymentByOrderId("order-1");

        assertFalse(result.handled());
        verify(transactionService, never()).loadRecoverySnapshot(any());
        verify(paymentGateway, never()).getPaymentByOrderId(anyString());
    }

    private Payment confirmingPayment(String orderId) {
        Payment payment = new Payment(newGroup(), 10000, NOW, orderId, "KRW");
        payment.confirming();
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        return payment;
    }

    private Payment approvedPayment(String orderId) {
        Payment payment = new Payment(newGroup(), 10000, NOW, orderId, "KRW");
        payment.confirming();
        payment.approve("pay-key-1", "CARD", "DONE");
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        return payment;
    }

    private ReservationGroup newGroup() {
        User user = new User("user@test.com", "password", "name", LocalDateTime.now());
        return new ReservationGroup(user, NOW, NOW.plusSeconds(300));
    }
}
