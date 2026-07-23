package com.jipi.ticket_ledger.payment.infrastructure;

import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayState;

public final class TossPaymentStatus {

    private TossPaymentStatus() {
    }

    public static boolean isApproved(String status) {
        return "DONE".equalsIgnoreCase(status);
    }

    public static boolean isCanceled(String status) {
        return "CANCELED".equalsIgnoreCase(status) || "PARTIAL_CANCELED".equalsIgnoreCase(status);
    }

    public static PaymentGatewayState toGatewayState(String status) {
        if (isApproved(status)) {
            return PaymentGatewayState.APPROVED;
        }
        if (isCanceled(status)) {
            return PaymentGatewayState.CANCELED;
        }
        if (isPending(status)) {
            return PaymentGatewayState.PENDING;
        }
        if (isFailed(status)) {
            return PaymentGatewayState.FAILED;
        }
        return PaymentGatewayState.UNKNOWN;
    }

    private static boolean isPending(String status) {
        return "READY".equalsIgnoreCase(status)
                || "IN_PROGRESS".equalsIgnoreCase(status)
                || "WAITING_FOR_DEPOSIT".equalsIgnoreCase(status);
    }

    private static boolean isFailed(String status) {
        return "ABORTED".equalsIgnoreCase(status)
                || "EXPIRED".equalsIgnoreCase(status);
    }
}
