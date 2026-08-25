package com.jipi.ticket_ledger.paymentaudit.application;

public class PaymentAuditEventValidationException extends RuntimeException {

    public PaymentAuditEventValidationException(String message) {
        super(message);
    }

    public PaymentAuditEventValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
