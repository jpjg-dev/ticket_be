package com.jipi.ticket_ledger.payment.application.observability;

import com.jipi.ticket_ledger.payment.application.cancel.CancelOutcome;
import com.jipi.ticket_ledger.payment.application.recovery.RecoveryOutcome;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * confirm/cancel 두 회색지대 보정을 대칭 이름으로 노출하는 얇은 Micrometer 래퍼.
 * MeterRegistry 는 actuator+prometheus 스타터가 자동 제공한다(현재 엔드포인트 미노출).
 * <ul>
 *   <li>payment_gray_zone_recovery_total{operation, outcome} — 보정 결과(approved/canceled/held_manual/…)</li>
 *   <li>payment_gray_zone_pg_failure_total{operation, call} — 외부 PG 호출 실패(lookup/cancel)</li>
 *   <li>payment_gray_zone_backlog{status} — CONFIRMING/CANCELING 잔량(스케줄러 주기마다 AtomicLong 갱신)</li>
 * </ul>
 */
@Component
public class PaymentRecoveryMetrics {

    private static final String RECOVERY_TOTAL = "payment_gray_zone_recovery_total";
    private static final String PG_FAILURE = "payment_gray_zone_pg_failure_total";
    private static final String BACKLOG = "payment_gray_zone_backlog";

    private static final String CONFIRM = "confirm";
    private static final String CANCEL = "cancel";
    private static final String LOOKUP = "lookup";

    private final MeterRegistry registry;
    private final AtomicLong confirmingBacklog = new AtomicLong();
    private final AtomicLong cancelingBacklog = new AtomicLong();

    public PaymentRecoveryMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder(BACKLOG, confirmingBacklog, AtomicLong::doubleValue)
                .tag("status", "confirming")
                .description("CONFIRMING 상태로 남아 보정을 기다리는 결제 수")
                .register(registry);
        Gauge.builder(BACKLOG, cancelingBacklog, AtomicLong::doubleValue)
                .tag("status", "canceling")
                .description("CANCELING 상태로 남아 보정을 기다리는 결제 수")
                .register(registry);
    }

    /** confirm 보정 결과를 기록한다. 외부 호출 실패는 pg_failure 로, 나머지는 recovery_total 로 라우팅한다. */
    public void record(RecoveryOutcome outcome) {
        switch (outcome) {
            case APPROVED, FAILED_RELEASED, REFUNDED_FAILED, PG_PROCESSING, HELD_MANUAL, SEAT_LOST_DEFERRED ->
                    recoveryTotal(CONFIRM, outcome.name());
            case LOOKUP_UNRESOLVED -> pgFailure(CONFIRM, LOOKUP);
            case REFUND_PENDING -> pgFailure(CONFIRM, CANCEL);
            case NOOP_NOT_CONFIRMING -> {
                // 무처리는 카운터 없음.
            }
        }
    }

    /** cancel 보정 결과를 기록한다(confirm 과 대칭). */
    public void record(CancelOutcome outcome) {
        switch (outcome) {
            case CANCELED, HELD_MANUAL, KEEP_CANCELING -> recoveryTotal(CANCEL, outcome.name());
            case LOOKUP_UNRESOLVED -> pgFailure(CANCEL, LOOKUP);
            case CANCEL_UNRESOLVED -> pgFailure(CANCEL, CANCEL);
            case NOOP_NOT_CANCELING -> {
                // 무처리는 카운터 없음.
            }
        }
    }

    /** 배치 per-item 이 예상 못 한 예외로 실패한 경우. operation 별로 recovery_total{outcome=batch_exception}. */
    public void recordBatchException(String operation) {
        recoveryTotal(operation, "batch_exception");
    }

    /** 스케줄러 주기마다 backlog gauge 를 갱신한다(스크레이프마다 DB 조회를 피하기 위해 AtomicLong 사용). */
    public void updateBacklog(long confirming, long canceling) {
        confirmingBacklog.set(confirming);
        cancelingBacklog.set(canceling);
    }

    private void recoveryTotal(String operation, String outcome) {
        registry.counter(RECOVERY_TOTAL, "operation", operation, "outcome", outcome.toLowerCase(Locale.ROOT)).increment();
    }

    private void pgFailure(String operation, String call) {
        registry.counter(PG_FAILURE, "operation", operation, "call", call).increment();
    }
}
