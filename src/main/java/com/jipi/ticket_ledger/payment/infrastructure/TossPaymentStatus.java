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
        return PaymentGatewayState.OTHER;
    }
}
