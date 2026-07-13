package com.jipi.ticket_ledger.payment.application.observability;

import com.jipi.ticket_ledger.payment.application.recovery.RecoveryOutcome;
import com.jipi.ticket_ledger.payment.application.cancel.CancelOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaymentRecoveryMetricsTest {

    private static final String RECOVERY_TOTAL = "payment_gray_zone_recovery_total";
    private static final String PG_FAILURE = "payment_gray_zone_pg_failure_total";
    private static final String BACKLOG = "payment_gray_zone_backlog";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PaymentRecoveryMetrics metrics = new PaymentRecoveryMetrics(registry);

    @Test
    @DisplayName("confirm 보정 결과 → recovery_total{operation=confirm, outcome} 증가(outcome 은 소문자)")
    void confirmRecoveryTotal() {
        metrics.record(RecoveryOutcome.APPROVED);
        metrics.record(RecoveryOutcome.FAILED_RELEASED);
        metrics.record(RecoveryOutcome.REFUNDED_FAILED);
        metrics.record(RecoveryOutcome.HELD_MANUAL);
        metrics.record(RecoveryOutcome.SEAT_LOST_DEFERRED);

        assertEquals(1.0, counter(RECOVERY_TOTAL, "operation", "confirm", "outcome", "approved"));
        assertEquals(1.0, counter(RECOVERY_TOTAL, "operation", "confirm", "outcome", "failed_released"));
        assertEquals(1.0, counter(RECOVERY_TOTAL, "operation", "confirm", "outcome", "refunded_failed"));
        assertEquals(1.0, counter(RECOVERY_TOTAL, "operation", "confirm", "outcome", "held_manual"));
        assertEquals(1.0, counter(RECOVERY_TOTAL, "operation", "confirm", "outcome", "seat_lost_deferred"));
    }

    @Test
    @DisplayName("confirm 외부호출 실패 → pg_failure{operation=confirm, call}(lookup=조회, cancel=환불)")
    void confirmPgFailure() {
        metrics.record(RecoveryOutcome.LOOKUP_UNRESOLVED);
        metrics.record(RecoveryOutcome.REFUND_PENDING);

        assertEquals(1.0, counter(PG_FAILURE, "operation", "confirm", "call", "lookup"));
        assertEquals(1.0, counter(PG_FAILURE, "operation", "confirm", "call", "cancel"));
    }

    @Test
    @DisplayName("cancel 보정 결과 → recovery_total{operation=cancel, outcome} 증가")
    void cancelRecoveryTotal() {
        metrics.record(CancelOutcome.CANCELED);
        metrics.record(CancelOutcome.HELD_MANUAL);
        metrics.record(CancelOutcome.KEEP_CANCELING);

        assertEquals(1.0, counter(RECOVERY_TOTAL, "operation", "cancel", "outcome", "canceled"));
        assertEquals(1.0, counter(RECOVERY_TOTAL, "operation", "cancel", "outcome", "held_manual"));
        assertEquals(1.0, counter(RECOVERY_TOTAL, "operation", "cancel", "outcome", "keep_canceling"));
    }

    @Test
    @DisplayName("cancel 외부호출 실패 → pg_failure{operation=cancel, call}(lookup=조회, cancel=취소호출)")
    void cancelPgFailure() {
        metrics.record(CancelOutcome.LOOKUP_UNRESOLVED);
        metrics.record(CancelOutcome.CANCEL_UNRESOLVED);

        assertEquals(1.0, counter(PG_FAILURE, "operation", "cancel", "call", "lookup"));
        assertEquals(1.0, counter(PG_FAILURE, "operation", "cancel", "call", "cancel"));
    }

    @Test
    @DisplayName("배치 예외 → recovery_total{operation, outcome=batch_exception} 증가")
    void batchExceptionCounter() {
        metrics.recordBatchException("confirm");
        metrics.recordBatchException("cancel");

        assertEquals(1.0, counter(RECOVERY_TOTAL, "operation", "confirm", "outcome", "batch_exception"));
        assertEquals(1.0, counter(RECOVERY_TOTAL, "operation", "cancel", "outcome", "batch_exception"));
    }

    @Test
    @DisplayName("NOOP 결과 → 어떤 카운터도 증가하지 않는다")
    void noopRecordsNothing() {
        metrics.record(RecoveryOutcome.NOOP_NOT_CONFIRMING);
        metrics.record(CancelOutcome.NOOP_NOT_CANCELING);

        assertEquals(0, registry.find(RECOVERY_TOTAL).counters().size());
        assertEquals(0, registry.find(PG_FAILURE).counters().size());
    }

    @Test
    @DisplayName("backlog gauge 는 updateBacklog 로 갱신한 AtomicLong 값을 반영한다")
    void backlogGaugeReflectsAtomicLong() {
        metrics.updateBacklog(7L, 4L);

        assertEquals(7.0, registry.get(BACKLOG).tag("status", "confirming").gauge().value());
        assertEquals(4.0, registry.get(BACKLOG).tag("status", "canceling").gauge().value());

        // 다음 주기 갱신이 반영되는지 확인.
        metrics.updateBacklog(0L, 2L);
        assertEquals(0.0, registry.get(BACKLOG).tag("status", "confirming").gauge().value());
        assertEquals(2.0, registry.get(BACKLOG).tag("status", "canceling").gauge().value());
    }

    private double counter(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }
}
