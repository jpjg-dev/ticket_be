package com.jipi.ticket_ledger.payment.application.port.out;

public enum PaymentEventPublishFailureKind {
    TRANSIENT,
    POISON,
    UNKNOWN
}
