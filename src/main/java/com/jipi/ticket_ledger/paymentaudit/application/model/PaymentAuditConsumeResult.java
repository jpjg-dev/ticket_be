package com.jipi.ticket_ledger.paymentaudit.application.model;

public record PaymentAuditConsumeResult(
        Outcome outcome,
        String eventType
) {
    public static PaymentAuditConsumeResult processed(String eventType) {
        return new PaymentAuditConsumeResult(Outcome.PROCESSED, eventType);
    }

    public static PaymentAuditConsumeResult duplicate(String eventType) {
        return new PaymentAuditConsumeResult(Outcome.DUPLICATE, eventType);
    }

    public enum Outcome {
        PROCESSED,
        DUPLICATE
    }
}
