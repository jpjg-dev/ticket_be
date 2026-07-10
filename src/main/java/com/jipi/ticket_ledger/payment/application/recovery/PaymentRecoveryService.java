package com.jipi.ticket_ledger.payment.application.recovery;

import com.jipi.ticket_ledger.payment.application.cancel.PaymentCancelService;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentClient;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentLookupResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentRecoveryService {

    private final PaymentRepository paymentRepository;
    private final TossPaymentClient tossPaymentClient;
    private final PaymentRecoveryTransactionService paymentRecoveryTransactionService;
    private final PaymentCancelService paymentCancelService;
    private final PaymentRecoveryMetrics paymentRecoveryMetrics;
    private final Clock clock;

    /**
     * CONFIRMING 회색지대 보정 1회. 배치·동기 경로가 공유한다.
     * (1) readonly 스냅샷 → (외부) PG 조회 → (2) 순수 결정 → (3) 필요 시 환불(락 밖) → (4) 락 하 적용 → metric.
     * <p>
     * PG 조회/환불의 RestClientException 은 내부에서 흡수해 CONFIRMING 을 유지하고(LOOKUP_UNRESOLVED/REFUND_PENDING)
     * 다음 주기에 재시도한다. 그 외 예외는 호출자(배치 격리/동기 전파)로 전파한다.
     */
    RecoveryOutcome recover(Long paymentId) {
        RecoverySnapshot snapshot = paymentRecoveryTransactionService.loadRecoverySnapshot(paymentId);
        if (snapshot == null) {
            return record(RecoveryOutcome.NOOP_NOT_CONFIRMING);
        }

        TossPaymentLookupResponse lookup;
        try {
            lookup = tossPaymentClient.getPaymentByOrderId(snapshot.orderId());
        } catch (RestClientException e) {
            // 조회 실패 로그는 TossPaymentClient 가 남긴다. 여기선 다음 주기 위임 결정만 남긴다.
            log.warn("Recovery lookup failed, leaving CONFIRMING for next cycle. paymentId={} orderId={}",
                    paymentId, snapshot.orderId());
            return record(RecoveryOutcome.LOOKUP_UNRESOLVED);
        }

        RecoveryDecision decision = RecoveryPolicy.decide(snapshot, lookup);

        if (decision.action() == RecoveryAction.HOLD_MANUAL) {
            // orderId 불일치: 우리 결제건이 맞는지 의심스러우므로 자동 조치하지 않고 알림 후 보류한다.
            log.error("CONFIRMING payment lookup orderId mismatch, manual review required. paymentId={} ourOrderId={} pgOrderId={} pgStatus={}",
                    paymentId, snapshot.orderId(), lookup.orderId(), lookup.status());
            return record(RecoveryOutcome.HELD_MANUAL);
        }

        if (decision.action() == RecoveryAction.REFUND_THEN_FAIL) {
            try {
                tossPaymentClient.cancel(
                        lookup.paymentKey(),
                        decision.refundReason(),
                        snapshot.currency(),
                        "cancel:" + paymentId
                );
            } catch (RestClientException e) {
                // 취소(환불) 호출 실패 로그는 TossPaymentClient 가 남긴다. CONFIRMING 유지 → 다음 주기 재시도.
                log.error("Refund failed for CONFIRMING payment, will retry next cycle. paymentId={} orderId={}",
                        paymentId, snapshot.orderId());
                return record(RecoveryOutcome.REFUND_PENDING);
            }
        }

        return record(paymentRecoveryTransactionService.applyDecision(paymentId, decision, lookup));
    }

    private RecoveryOutcome record(RecoveryOutcome outcome) {
        paymentRecoveryMetrics.record(outcome);
        return outcome;
    }

    /**
     * 컨트롤러 confirm 실패 시 호출하는 단건 동기 재조회.
     * 결제가 CONFIRMING 회색지대일 때만 스케줄러와 동일한 보정 로직을 즉시 1회 적용한다.
     * PG가 여전히 응답하지 않으면 CONFIRMING으로 두고 보정 스케줄러에 위임한다.
     *
     * @return handled=true 이면 CONFIRMING 회색지대를 처리한 것(현재 상태를 그대로 응답해도 됨),
     *         handled=false 이면 confirm 진입 전 정상 비즈니스 거절이므로 호출 측에서 원래 예외를 전파해야 한다.
     */
    public SyncReconcileResult reconcileConfirmingPaymentByOrderId(String orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.CONFIRMING) {
            return new SyncReconcileResult(payment, false);
        }

        // 조회/환불 RestClientException 은 recover 내부에서 흡수(CONFIRMING 유지)하므로 여기서 잡을 필요가 없다.
        // 그 외 예외만 전파된다(기존 동기 시맨틱 보존).
        recover(payment.getId());

        Payment resolved = paymentRepository.findByOrderId(orderId).orElse(payment);
        return new SyncReconcileResult(resolved, true);
    }

    public record SyncReconcileResult(Payment payment, boolean handled) {
    }

    @Transactional(readOnly = true)
    public List<Long> findStaleConfirmingPaymentIds(Duration grace, int batchSize) {
        return paymentRepository.findStaleConfirmingIds(
                clock.instant().minus(grace),
                PageRequest.of(0, batchSize)
        );
    }

    @Transactional(readOnly = true)
    public List<Long> findStaleCancelingPaymentIds(Duration grace, int batchSize) {
        return paymentRepository.findStaleCancelingIds(
                clock.instant().minus(grace),
                PageRequest.of(0, batchSize)
        );
    }

    public int reconcileStaleConfirmingPayments(Duration grace, int batchSize) {
        return runRecoveryBatch("confirm", findStaleConfirmingPaymentIds(grace, batchSize),
                paymentId -> recover(paymentId).isRecovered());
    }

    public int reconcileStaleCancelingPayments(Duration grace, int batchSize) {
        return runRecoveryBatch("cancel", findStaleCancelingPaymentIds(grace, batchSize),
                paymentId -> paymentCancelService.recoverCanceling(paymentId).isRecovered());
    }

    /** 스케줄러 주기마다 CONFIRMING/CANCELING 잔량을 세어 backlog gauge 를 갱신한다(스크레이프마다 DB 조회 회피). */
    public void updateBacklogGauges() {
        long confirming = paymentRepository.countByStatus(PaymentStatus.CONFIRMING);
        long canceling = paymentRepository.countByStatus(PaymentStatus.CANCELING);
        paymentRecoveryMetrics.updateBacklog(confirming, canceling);
    }

    /**
     * 회색지대 보정 배치 공용 루프. (id 리스트, per-item 보정 함수) → per-item 예외격리 + recovered 집계.
     * 한 건의 실패(예상 못 한 오류 등)가 배치 전체를 멈추지 않도록 건별로 감싸고, 실패 건은 다음 주기에 재시도된다.
     * confirm/cancel 두 배치가 공유한다(제네릭 프레임워크가 아니라 private 메서드 하나).
     *
     * @param recoverFn per-item 보정 함수, 반환값은 터미널 수렴 여부(recoveredCount 집계용)
     */
    private int runRecoveryBatch(String operation, List<Long> paymentIds, Predicate<Long> recoverFn) {
        int recoveredCount = 0;
        int failedCount = 0;
        for (Long paymentId : paymentIds) {
            try {
                if (recoverFn.test(paymentId)) {
                    recoveredCount++;
                }
            } catch (Exception e) {
                failedCount++;
                paymentRecoveryMetrics.recordBatchException(operation);
                // 실패 건은 회색지대로 남아 다음 주기에 재시도되며, 매번 노출되도록 크게 로깅한다.
                log.error("Failed to reconcile {} gray-zone payment, skipping to next. paymentId={}", operation, paymentId, e);
            }
        }
        if (!paymentIds.isEmpty()) {
            log.info("Reconcile {} gray-zone payment batch finished. candidateCount={} recoveredCount={} failedCount={}",
                    operation, paymentIds.size(), recoveredCount, failedCount);
        }
        return recoveredCount;
    }
}
