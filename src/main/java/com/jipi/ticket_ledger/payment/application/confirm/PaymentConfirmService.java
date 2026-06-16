package com.jipi.ticket_ledger.payment.application.confirm;

import com.jipi.ticket_ledger.global.log.LogEvents;
import com.jipi.ticket_ledger.payment.application.PaymentLogFormatter;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.infrastructure.TossConfirmResponse;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentClient;
import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentLookupResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentConfirmService {

    private final TossPaymentClient tossPaymentClient;
    private final PaymentConfirmTransactionService paymentConfirmTransactionService;

    public Payment confirm(String paymentKey, String orderId, Integer amount) {
        // Tx1 부분
        ConfirmingPayment confirmingPayment = paymentConfirmTransactionService.markConfirming(paymentKey, orderId, amount);
        if (confirmingPayment.alreadyApproved()) {
            return paymentConfirmTransactionService.getPayment(confirmingPayment.orderId());
        }

        PaymentPgApproval approval;
        try {
            approval = confirmWithPg(paymentKey, confirmingPayment);
        } catch (RestClientException confirmException) {
            log.error("event={} orderId={} paymentId={} reservationGroupId={} reason={} paymentKeyMasked={}",
                    LogEvents.PAYMENT_CONFIRM_REJECT, confirmingPayment.orderId(), confirmingPayment.paymentId(),
                    confirmingPayment.reservationGroupId(), "PG_CONFIRM_EXCEPTION",
                    PaymentLogFormatter.maskPaymentKey(paymentKey), confirmException);
            PaymentPgApproval lookupApproval = PaymentPgApproval.from(confirmPaymentStatus(paymentKey, confirmingPayment.orderId()));
            if (PaymentPgApprovalValidator.isApprovedLookup(confirmingPayment, lookupApproval)) {
                return paymentConfirmTransactionService.applyApproved(confirmingPayment, lookupApproval);
            }
            throw confirmException;
        }

        PaymentPgApprovalValidator.validate(paymentKey, confirmingPayment, approval);
        return paymentConfirmTransactionService.applyApproved(confirmingPayment, approval);
    }

    private PaymentPgApproval confirmWithPg(String paymentKey, ConfirmingPayment confirmingPayment) {
        TossConfirmResponse tossResponse = tossPaymentClient.confirm(
                paymentKey,
                confirmingPayment.orderId(),
                confirmingPayment.totalAmountWithVat(),
                createConfirmIdempotencyKey(confirmingPayment.orderId())
        );
        return PaymentPgApproval.from(tossResponse);
    }

    private TossPaymentLookupResponse confirmPaymentStatus(String paymentKey, String orderId) {
        if (paymentKey != null && !paymentKey.isBlank()) {
            return tossPaymentClient.getPaymentByPaymentKey(paymentKey);
        }
        return tossPaymentClient.getPaymentByOrderId(orderId);
    }

    private String createConfirmIdempotencyKey(String orderId) {
        return "confirm:" + orderId;
    }
}
