package com.jipi.ticket_ledger.payment.application.cancel;

import com.jipi.ticket_ledger.global.log.LogEvents;
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
 * 취소 동기 경로 오케스트레이션(트랜잭션 없음). confirm 과 동일한 3단계:
 * mark(write tx) → 외부 PG 호출(tx 밖) → apply(재락 + 재확인 write tx).
 * <p>
 * markCanceling 을 통과하면(비 멱등 스냅샷) 결제는 최소 CANCELING durable 상태가 보장되며,
 * 이후 어떤 경로도 결제를 APPROVED 로 되돌리지 않는다(돈 안전 불변식). PG 취소가 확인되면 CANCELED 로 확정하고,
 * 확인되지 않으면 CANCELING 을 유지한 채 그 상태를 반환한다(동기에선 재시도 루프를 돌지 않고 Phase 3 스케줄러가 수렴).
 * 사전검사(소유자/상태/paymentKey) 실패는 예외로 전파된다. apply 단계 도메인 가드(예: 빈 예매)에서도 예외가 날 수 있으나,
 * 그 경우에도 결제는 APPROVED 로 되돌아가지 않고 CANCELING 마커가 유지된다.
 *
 * @return 확정된 최종 상태 — FINALIZE(또는 이미 CANCELED) 시 {@code CANCELED}, 그 외 미확정/보류는 {@code CANCELING}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentCancelService {

    private final TossPaymentClient tossPaymentClient;
    private final PaymentCancelTransactionService paymentCancelTransactionService;

    public PaymentStatus cancel(Long paymentId, String cancelReason, Long requesterUserId) {
        CancelingPaymentSnapshot snapshot = paymentCancelTransactionService.markCanceling(paymentId, requesterUserId);
        if (snapshot.alreadyCanceled()) {
            // 이미 CANCELED → 멱등 즉시 종료(외부 호출 없음).
            return PaymentStatus.CANCELED;
        }

        PgCancelState pgState = requestCancelOrLookup(snapshot, cancelReason);
        if (pgState == null) {
            // PG 취소·조회가 모두 미확정(timeout 등) → CANCELING 유지, 다음 주기(스케줄러) 위임.
            return PaymentStatus.CANCELING;
        }

        CancelDecision decision = PaymentCancelPolicy.decide(snapshot, pgState);
        if (decision.action() == CancelAction.FINALIZE) {
            paymentCancelTransactionService.applyDecision(paymentId, decision);
            return PaymentStatus.CANCELED;
        }

        // CANCEL_AGAIN / HOLD_MANUAL → CANCELING 유지, 무쓰기(동기 재시도 안 함).
        if (decision.action() == CancelAction.HOLD_MANUAL) {
            log.error("event={} orderId={} paymentId={} reservationGroupId={} reason={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CANCEL_REJECT, snapshot.orderId(), snapshot.paymentId(), snapshot.reservationGroupId(),
                    "PG_CANCEL_HOLD_MANUAL", PaymentLogFormatter.maskPaymentKey(snapshot.paymentKey()));
        } else {
            log.warn("event={} orderId={} paymentId={} reservationGroupId={} reason={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CANCEL_REJECT, snapshot.orderId(), snapshot.paymentId(), snapshot.reservationGroupId(),
                    "PG_CANCEL_NOT_YET_KEEP_CANCELING", PaymentLogFormatter.maskPaymentKey(snapshot.paymentKey()));
        }
        return PaymentStatus.CANCELING;
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
            log.warn("event={} orderId={} paymentId={} reservationGroupId={} reason={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CANCEL_REJECT, snapshot.orderId(), snapshot.paymentId(), snapshot.reservationGroupId(),
                    "PG_CANCEL_FALLBACK_LOOKUP", PaymentLogFormatter.maskPaymentKey(snapshot.paymentKey()));
            try {
                TossPaymentLookupResponse lookup = tossPaymentClient.getPaymentByPaymentKey(snapshot.paymentKey());
                return PgCancelState.from(lookup);
            } catch (RestClientException lookupException) {
                // 조회까지 실패(timeout 등) → CANCELING 유지, 예외 삼킴(Phase 3 스케줄러가 수렴).
                log.warn("event={} orderId={} paymentId={} reservationGroupId={} reason={} paymentKeyMasked={}",
                        LogEvents.PAYMENT_CANCEL_REJECT, snapshot.orderId(), snapshot.paymentId(), snapshot.reservationGroupId(),
                        "PG_CANCEL_LOOKUP_UNRESOLVED_KEEP_CANCELING", PaymentLogFormatter.maskPaymentKey(snapshot.paymentKey()));
                return null;
            }
        }
    }

    private String createCancelIdempotencyKey(Long paymentId) {
        return "cancel:" + paymentId;
    }
}
