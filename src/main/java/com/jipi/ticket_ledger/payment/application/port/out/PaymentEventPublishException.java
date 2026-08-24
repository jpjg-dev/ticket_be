package com.jipi.ticket_ledger.payment.application.port.out;

import lombok.Getter;

@Getter
public class PaymentEventPublishException extends RuntimeException {

    private final PaymentEventPublishFailureKind failureKind;
    private final String errorCode;

    public PaymentEventPublishException(
            PaymentEventPublishFailureKind failureKind,
            String errorCode,
            Throwable cause
    ) {
        super(errorCode, cause);
        this.failureKind = failureKind;
        this.errorCode = errorCode;
    }
}
