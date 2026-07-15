package com.jipi.ticket_ledger.payment.application.port.out;

public class PaymentGatewayTemporarilyUnavailableException extends PaymentGatewayException {

    private final long retryAfterSeconds;

    public PaymentGatewayTemporarilyUnavailableException(String message, long retryAfterSeconds, Throwable cause) {
        super(message, cause);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
