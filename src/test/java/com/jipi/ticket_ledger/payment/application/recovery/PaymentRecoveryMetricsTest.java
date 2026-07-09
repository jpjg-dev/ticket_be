package com.jipi.ticket_ledger.payment.application.recovery;

import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentRecoveryMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentRecoveryMetrics metrics = new PaymentRecoveryMetrics(registry, paymentRepository);

    @Test
    @DisplayName("APPROVED/FAILED_RELEASED/REFUNDED_FAILED → payment_recovery_total{result} 증가")
    void totalCounters() {
        metrics.record(RecoveryOutcome.APPROVED);
        metrics.record(RecoveryOutcome.FAILED_RELEASED);
        metrics.record(RecoveryOutcome.REFUNDED_FAILED);

        assertEquals(1.0, registry.get("payment_recovery_total").tag("result", "APPROVED").counter().count());
        assertEquals(1.0, registry.get("payment_recovery_total").tag("result", "FAILED_RELEASED").counter().count());
        assertEquals(1.0, registry.get("payment_recovery_total").tag("result", "REFUNDED_FAILED").counter().count());
    }

    @Test
    @DisplayName("SEAT_LOST_DEFERRED/HELD_MANUAL/LOOKUP_UNRESOLVED → payment_recovery_failed_total{reason} 증가")
    void failedCounters() {
        metrics.record(RecoveryOutcome.SEAT_LOST_DEFERRED);
        metrics.record(RecoveryOutcome.HELD_MANUAL);
        metrics.record(RecoveryOutcome.LOOKUP_UNRESOLVED);

        assertEquals(1.0, registry.get("payment_recovery_failed_total").tag("reason", "SEAT_LOST_DEFERRED").counter().count());
        assertEquals(1.0, registry.get("payment_recovery_failed_total").tag("reason", "HELD_MANUAL").counter().count());
        assertEquals(1.0, registry.get("payment_recovery_failed_total").tag("reason", "LOOKUP_UNRESOLVED").counter().count());
    }

    @Test
    @DisplayName("배치 예외 → payment_recovery_failed_total{reason=BATCH_EXCEPTION} 증가")
    void batchExceptionCounter() {
        metrics.recordBatchException();

        assertEquals(1.0, registry.get("payment_recovery_failed_total").tag("reason", "BATCH_EXCEPTION").counter().count());
    }

    @Test
    @DisplayName("REFUND_PENDING → payment_recovery_refund_failed_total 증가")
    void refundFailedCounter() {
        metrics.record(RecoveryOutcome.REFUND_PENDING);

        assertEquals(1.0, registry.get("payment_recovery_refund_failed_total").counter().count());
    }

    @Test
    @DisplayName("NOOP_NOT_CONFIRMING → 어떤 카운터도 증가하지 않음")
    void noopRecordsNothing() {
        metrics.record(RecoveryOutcome.NOOP_NOT_CONFIRMING);

        assertEquals(0, registry.find("payment_recovery_total").counters().size());
        assertEquals(0, registry.find("payment_recovery_failed_total").counters().size());
    }

    @Test
    @DisplayName("gauge payment_confirming_backlog 는 countByStatus(CONFIRMING) 을 반영한다")
    void backlogGauge() {
        when(paymentRepository.countByStatus(PaymentStatus.CONFIRMING)).thenReturn(7L);

        assertEquals(7.0, registry.get("payment_confirming_backlog").gauge().value());
    }
}
