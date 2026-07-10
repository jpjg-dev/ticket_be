package com.jipi.ticket_ledger.payment.application.cancel;

import com.jipi.ticket_ledger.global.log.LogEvents;
import com.jipi.ticket_ledger.payment.application.recovery.PaymentRecoveryMetrics;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.payment.infrastructure.PaymentLogFormatter;
import com.jipi.ticket_ledger.payment.infrastructure.TossCancelResponse;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentClient;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentLookupResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

/**
 * 취소 오케스트레이션(트랜잭션 없음). confirm 과 동일한 3단계:
 * mark(write tx) → 외부 PG 호출(tx 밖) → apply(재락 + 재확인 write tx).
 * <p>
 * markCanceling 을 통과하면(비 멱등 스냅샷) 결제는 최소 CANCELING durable 상태가 보장되며,
 * 이후 어떤 경로도 결제를 APPROVED 로 되돌리지 않는다(돈 안전 불변식). PG 취소가 확인되면 CANCELED 로 확정하고,
 * 확인되지 않으면 CANCELING 을 유지한 채 그 상태를 반환한다(동기에선 재시도 루프를 돌지 않고 Phase 3 스케줄러가 수렴).
 * <p>
 * {@link #cancel} 동기 경로와 {@link #recoverCanceling} 비동기 보정 경로는 재취소 파이프라인
 * ({@link #requestCancelDecideApply})를 같은 멱등키({@code "cancel:"+id})로 공유한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentCancelService {

    // 보정 경로에서 재취소를 발행할 때 쓰는 사유(동기 경로의 사용자 사유가 없을 때). 멱등키가 같아 Toss 가 원취소로 dedupe 한다.
    private static final String RECOVERY_CANCEL_REASON = "CANCEL_RECOVERY";

    private final TossPaymentClient tossPaymentClient;
    private final PaymentCancelTransactionService paymentCancelTransactionService;
    private final PaymentRecoveryMetrics paymentRecoveryMetrics;

    /**
     * 동기 취소. 소유자 검증·멱등 단축·반환 시맨틱은 P2 그대로다(메트릭은 비동기 보정 경로에서만 기록).
     *
     * @return 확정된 최종 상태 — FINALIZE(또는 이미 CANCELED) 시 {@code CANCELED}, 그 외 미확정/보류는 {@code CANCELING}.
     */
    public PaymentStatus cancel(Long paymentId, String cancelReason, Long requesterUserId) {
        CancelingPaymentSnapshot snapshot = paymentCancelTransactionService.markCanceling(paymentId, requesterUserId);
        if (snapshot.alreadyCanceled()) {
            // 이미 CANCELED → 멱등 즉시 종료(외부 호출 없음).
            return PaymentStatus.CANCELED;
        }

        CancelOutcome outcome = requestCancelDecideApply(snapshot, cancelReason);
        return outcome == CancelOutcome.CANCELED ? PaymentStatus.CANCELED : PaymentStatus.CANCELING;
    }

    /**
     * CANCELING 회색지대 보정 1회(트랜잭션 없음). 스케줄러 배치가 stuck CANCELING 결제마다 호출한다.
     * <ol>
     *   <li>readonly 스냅샷 로드 → null 이면 skip(이미 해소)</li>
     *   <li>paymentKey 로 PG 조회(tx 밖). 실패 시 무쓰기 CANCELING 유지</li>
     *   <li>정책 결정: FINALIZE → apply / CANCEL_AGAIN → 동기와 같은 재취소 파이프라인 공유 / HOLD_MANUAL → 무쓰기</li>
     * </ol>
     * confirm 보정과 달리 APPROVED 복귀(REVERT)는 존재하지 않는다 — 탈출구는 CANCELED(또는 수동 HOLD)뿐이다.
     */
    public CancelOutcome recoverCanceling(Long paymentId) {
        CancelingPaymentSnapshot snapshot = paymentCancelTransactionService.loadCancelingSnapshot(paymentId);
        if (snapshot == null) {
            // 이미 CANCELED 되었거나 애초에 CANCELING 이 아님 → 무처리.
            return record(CancelOutcome.NOOP_NOT_CANCELING);
        }

        TossPaymentLookupResponse lookup;
        try {
            lookup = tossPaymentClient.getPaymentByPaymentKey(snapshot.paymentKey());
        } catch (RestClientException lookupException) {
            // 조회 실패 로그는 TossPaymentClient 가 남긴다. CANCELING 유지 → 다음 주기 재시도.
            logCancelReject(snapshot, "PG_CANCEL_RECOVERY_LOOKUP_UNRESOLVED_KEEP_CANCELING");
            return record(CancelOutcome.LOOKUP_UNRESOLVED);
        }

        CancelDecision decision = PaymentCancelPolicy.decide(snapshot, PgCancelState.from(lookup));
        return switch (decision.action()) {
            case FINALIZE -> {
                paymentCancelTransactionService.applyDecision(snapshot.paymentId(), decision);
                yield record(CancelOutcome.CANCELED);
            }
            // 아직 승인(DONE) → 동기 경로와 동일한 재취소 파이프라인을 같은 멱등키로 공유한다.
            case CANCEL_AGAIN -> record(requestCancelDecideApply(snapshot, RECOVERY_CANCEL_REASON));
            case HOLD_MANUAL -> {
                logCancelReject(snapshot, "PG_CANCEL_RECOVERY_HOLD_MANUAL");
                yield record(CancelOutcome.HELD_MANUAL);
            }
        };
    }

    /**
     * 재취소 파이프라인(동기·보정 공유): PG 취소 호출(실패 시 조회 폴백) → 정책 재결정 → FINALIZE 면 apply, 아니면 CANCELING 유지.
     * 메트릭은 여기서 기록하지 않는다(호출자가 결정) — 동기 {@link #cancel} 은 메트릭 없이, {@link #recoverCanceling} 은 기록한다.
     */
    private CancelOutcome requestCancelDecideApply(CancelingPaymentSnapshot snapshot, String cancelReason) {
        PgCancelState pgState = requestCancelOrLookup(snapshot, cancelReason);
        if (pgState == null) {
            // PG 취소 호출이 실패했고 폴백 조회도 미확정(timeout 등) → CANCELING 유지.
            // 순수 조회 실패(LOOKUP_UNRESOLVED)와 구분해 취소 호출 실패를 관측 가능하게 한다.
            return CancelOutcome.CANCEL_UNRESOLVED;
        }

        CancelDecision decision = PaymentCancelPolicy.decide(snapshot, pgState);
        if (decision.action() == CancelAction.FINALIZE) {
            paymentCancelTransactionService.applyDecision(snapshot.paymentId(), decision);
            return CancelOutcome.CANCELED;
        }
        if (decision.action() == CancelAction.HOLD_MANUAL) {
            logCancelReject(snapshot, "PG_CANCEL_HOLD_MANUAL", true);
            return CancelOutcome.HELD_MANUAL;
        }
        // CANCEL_AGAIN → 아직 취소가 안 먹음(여전히 DONE). CANCELING 유지, 무쓰기.
        logCancelReject(snapshot, "PG_CANCEL_NOT_YET_KEEP_CANCELING");
        return CancelOutcome.KEEP_CANCELING;
    }

    /**
     * PG 취소를 호출하고, 실패(RestClientException) 시 조회로 최종 상태를 확인한다.
     * 취소·조회 모두 미확정이면 null 을 반환해 호출자가 CANCELING 을 유지하게 한다.
     */
    private PgCancelState requestCancelOrLookup(CancelingPaymentSnapshot snapshot, String cancelReason) {
        try {
            TossCancelResponse response = tossPaymentClient.cancel(
                    snapshot.paymentKey(),
                    cancelReason,
                    snapshot.currency(),
                    createCancelIdempotencyKey(snapshot.paymentId())
            );
            return PgCancelState.from(response);
        } catch (RestClientException cancelException) {
            // PG 취소 호출 실패 로그는 TossPaymentClient 가 남긴다. 여기선 조회 fallback 결정만 남긴다.
            logCancelReject(snapshot, "PG_CANCEL_FALLBACK_LOOKUP");
            try {
                TossPaymentLookupResponse lookup = tossPaymentClient.getPaymentByPaymentKey(snapshot.paymentKey());
                return PgCancelState.from(lookup);
            } catch (RestClientException lookupException) {
                // 조회까지 실패(timeout 등) → CANCELING 유지, 예외 삼킴(스케줄러가 수렴).
                logCancelReject(snapshot, "PG_CANCEL_LOOKUP_UNRESOLVED_KEEP_CANCELING");
                return null;
            }
        }
    }

    private CancelOutcome record(CancelOutcome outcome) {
        paymentRecoveryMetrics.record(outcome);
        return outcome;
    }

    private void logCancelReject(CancelingPaymentSnapshot snapshot, String reason) {
        logCancelReject(snapshot, reason, false);
    }

    private void logCancelReject(CancelingPaymentSnapshot snapshot, String reason, boolean error) {
        String format = "event={} orderId={} paymentId={} reservationGroupId={} reason={} paymentKeyMasked={}";
        Object[] args = {
                LogEvents.PAYMENT_CANCEL_REJECT, snapshot.orderId(), snapshot.paymentId(), snapshot.reservationGroupId(),
                reason, PaymentLogFormatter.maskPaymentKey(snapshot.paymentKey())
        };
        if (error) {
            log.error(format, args);
        } else {
            log.warn(format, args);
        }
    }

    private String createCancelIdempotencyKey(Long paymentId) {
        return "cancel:" + paymentId;
    }
}
