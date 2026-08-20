package com.jipi.ticket_ledger.payment.infrastructure.outbox;

public enum PaymentOutboxStatus {
    PENDING,
    PUBLISHED,
    HOLD_MANUAL
}
