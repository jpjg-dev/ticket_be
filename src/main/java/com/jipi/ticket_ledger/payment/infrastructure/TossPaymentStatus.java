package com.jipi.ticket_ledger.payment.infrastructure;

public final class TossPaymentStatus {

    private TossPaymentStatus() {
    }

    public static boolean isApproved(String status) {
        return "DONE".equalsIgnoreCase(status);
    }

    public static boolean isCanceled(String status) {
        return "CANCELED".equalsIgnoreCase(status) || "PARTIAL_CANCELED".equalsIgnoreCase(status);
    }
}
