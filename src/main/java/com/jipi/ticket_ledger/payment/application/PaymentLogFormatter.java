package com.jipi.ticket_ledger.payment.application;

public final class PaymentLogFormatter {

    private PaymentLogFormatter() {
    }

    public static String maskPaymentKey(String paymentKey) {
        if (paymentKey == null || paymentKey.isBlank()) {
            return "N/A";
        }
        int visiblePrefix = Math.min(6, paymentKey.length());
        return paymentKey.substring(0, visiblePrefix) + "*".repeat(Math.max(0, paymentKey.length() - visiblePrefix));
    }
}
