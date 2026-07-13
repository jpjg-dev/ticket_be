package com.jipi.ticket_ledger.payment.application.confirm;

import com.jipi.ticket_ledger.global.log.LogEvents;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGateway;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayException;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayPayment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentConfirmService {

    private final PaymentGateway paymentGateway;
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
        } catch (PaymentGatewayException confirmException) {
            // confirm 외부호출 실패 로그는 PG 어댑터가 남긴다. 여기선 회색지대 대응(조회 fallback) 결정만 남긴다.
            log.warn("event={} orderId={} paymentId={} reservationGroupId={} reason={}",
                    LogEvents.PAYMENT_CONFIRM_REJECT, confirmingPayment.orderId(), confirmingPayment.paymentId(),
                    confirmingPayment.reservationGroupId(), "PG_CONFIRM_FALLBACK_LOOKUP");
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
        PaymentGatewayPayment response = paymentGateway.confirm(
                paymentKey,
                confirmingPayment.orderId(),
                confirmingPayment.totalAmountWithVat(),
                createConfirmIdempotencyKey(confirmingPayment.orderId())
        );
        return PaymentPgApproval.from(response);
    }

    private PaymentGatewayPayment confirmPaymentStatus(String paymentKey, String orderId) {
        if (paymentKey != null && !paymentKey.isBlank()) {
            return paymentGateway.getPaymentByPaymentKey(paymentKey);
        }
        return paymentGateway.getPaymentByOrderId(orderId);
    }

    private String createConfirmIdempotencyKey(String orderId) {
        return "confirm:" + orderId;
    }
}
