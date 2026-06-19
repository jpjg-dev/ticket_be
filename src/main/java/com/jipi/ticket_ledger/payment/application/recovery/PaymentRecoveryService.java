package com.jipi.ticket_ledger.payment.application.recovery;

import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentRecoveryService {

    private final PaymentRepository paymentRepository;
    private final TossPaymentClient tossPaymentClient;
    private final PaymentRecoveryTransactionService paymentRecoveryTransactionService;

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

        try {
            paymentRecoveryTransactionService.applyLookupResult(
                    payment.getId(),
                    tossPaymentClient.getPaymentByOrderId(orderId)
            );
        } catch (RestClientException e) {
            log.warn("Synchronous reconcile lookup failed, leaving CONFIRMING for scheduler. orderId={}", orderId, e);
        }

        Payment resolved = paymentRepository.findByOrderId(orderId).orElse(payment);
        return new SyncReconcileResult(resolved, true);
    }

    public record SyncReconcileResult(Payment payment, boolean handled) {
    }

    @Transactional(readOnly = true)
    public List<Long> findStaleConfirmingPaymentIds(Duration grace) {
        return paymentRepository.findStaleConfirmingIds(Instant.now().minus(grace));
    }

    public int reconcileStaleConfirmingPayments(Duration grace) {
        int recoveredCount = 0;
        for (Long paymentId : findStaleConfirmingPaymentIds(grace)) {
            try {
                if (reconcileConfirmingPayment(paymentId)) {
                    recoveredCount++;
                }
            } catch (Exception e) {
                // 한 건의 실패(PG 오류·데이터 이상 등)가 배치 전체를 멈추지 않도록 격리한다.
                // 실패 건은 CONFIRMING으로 남아 다음 주기에 재시도되며, 매번 노출되도록 크게 로깅한다.
                log.error("Failed to reconcile CONFIRMING payment, skipping to next. paymentId={}", paymentId, e);
            }
        }
        return recoveredCount;
    }

    private boolean reconcileConfirmingPayment(Long paymentId) {
        ConfirmingPaymentCandidate candidate = paymentRecoveryTransactionService.loadConfirmingCandidate(paymentId);
        if (candidate == null) {
            return false;
        }

        return paymentRecoveryTransactionService.applyLookupResult(
                paymentId,
                tossPaymentClient.getPaymentByOrderId(candidate.orderId())
        );
    }
}
