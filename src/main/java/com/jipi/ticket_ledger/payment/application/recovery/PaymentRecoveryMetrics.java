package com.jipi.ticket_ledger.payment.application.recovery;

import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 보정 결과를 Micrometer 로 노출하는 얇은 래퍼. MeterRegistry 는 actuator+prometheus 스타터가 자동 제공한다.
 * <ul>
 *   <li>payment_recovery_total{result} — 터미널 수렴(APPROVED/FAILED_RELEASED/REFUNDED_FAILED)</li>
 *   <li>payment_recovery_failed_total{reason} — 미수렴/보류(SEAT_LOST_DEFERRED/HELD_MANUAL/LOOKUP_UNRESOLVED/BATCH_EXCEPTION)</li>
 *   <li>payment_recovery_refund_failed_total — 환불 호출 실패(REFUND_PENDING)</li>
 *   <li>payment_confirming_backlog (gauge) — 현재 CONFIRMING 잔량(countByStatus)</li>
 * </ul>
 */
@Component
class PaymentRecoveryMetrics {

    private static final String TOTAL = "payment_recovery_total";
    private static final String FAILED = "payment_recovery_failed_total";
    private static final String REFUND_FAILED = "payment_recovery_refund_failed_total";
    private static final String BACKLOG = "payment_confirming_backlog";

    private final MeterRegistry registry;

    PaymentRecoveryMetrics(MeterRegistry registry, PaymentRepository paymentRepository) {
        this.registry = registry;
        Gauge.builder(BACKLOG, paymentRepository, repo -> repo.countByStatus(PaymentStatus.CONFIRMING))
                .description("CONFIRMING 상태로 남아 보정을 기다리는 결제 수")
                .register(registry);
    }

    void record(RecoveryOutcome outcome) {
        switch (outcome) {
            case APPROVED, FAILED_RELEASED, REFUNDED_FAILED ->
                    registry.counter(TOTAL, "result", outcome.name()).increment();
            case SEAT_LOST_DEFERRED, HELD_MANUAL, LOOKUP_UNRESOLVED ->
                    registry.counter(FAILED, "reason", outcome.name()).increment();
            case REFUND_PENDING ->
                    registry.counter(REFUND_FAILED).increment();
            case NOOP_NOT_CONFIRMING -> {
                // 무처리는 카운터 없음.
            }
        }
    }

    void recordBatchException() {
        registry.counter(FAILED, "reason", "BATCH_EXCEPTION").increment();
    }
}
