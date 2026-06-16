package com.jipi.ticket_ledger.payment.application.recovery;

import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentRecoveryService {

    private final PaymentRepository paymentRepository;
    private final TossPaymentClient tossPaymentClient;
    private final PaymentRecoveryTransactionService paymentRecoveryTransactionService;

    @Transactional(readOnly = true)
    public List<Long> findStaleConfirmingPaymentIds(Duration grace) {
        return paymentRepository.findStaleConfirmingIds(LocalDateTime.now().minus(grace));
    }

    public int reconcileStaleConfirmingPayments(Duration grace) {
        int recoveredCount = 0;
        for (Long paymentId : findStaleConfirmingPaymentIds(grace)) {
            if (reconcileConfirmingPayment(paymentId)) {
                recoveredCount++;
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
