package com.jipi.ticket_ledger.payment.application.confirm;

import com.jipi.ticket_ledger.payment.infrastructure.TossPaymentStatus;

import java.util.Objects;

final class PaymentPgApprovalValidator {

    private PaymentPgApprovalValidator() {
    }

    static void validate(String requestedPaymentKey, ConfirmingPayment confirmingPayment, PaymentPgApproval approval) {
        if (!confirmingPayment.totalAmountWithVat().equals(approval.totalAmount())) {
            throw new IllegalStateException("PG 승인 금액이 일치하지 않습니다.");
        }

        if (!confirmingPayment.currency().equals(approval.currency())) {
            throw new IllegalStateException("PG 통화 코드가 일치하지 않습니다.");
        }

        if (!Objects.equals(requestedPaymentKey, approval.paymentKey())) {
            throw new IllegalStateException("PG 승인 응답 결제키가 일치하지 않습니다.");
        }

        if (!Objects.equals(confirmingPayment.orderId(), approval.orderId())) {
            throw new IllegalStateException("PG 승인 응답 주문번호가 일치하지 않습니다.");
        }

        if (!TossPaymentStatus.isApproved(approval.status())) {
            throw new IllegalStateException("PG 승인 상태가 유효하지 않습니다.");
        }
    }

    static boolean isApprovedLookup(ConfirmingPayment confirmingPayment, PaymentPgApproval approval) {
        return TossPaymentStatus.isApproved(approval.status())
                && confirmingPayment.orderId().equals(approval.orderId())
                && confirmingPayment.totalAmountWithVat().equals(approval.totalAmount())
                && confirmingPayment.currency().equals(approval.currency());
    }
}
