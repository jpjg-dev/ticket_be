package com.jipi.ticket_ledger.global.log;

// 결제 로그의 민감값 마스킹을 한곳에 모은다.
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
