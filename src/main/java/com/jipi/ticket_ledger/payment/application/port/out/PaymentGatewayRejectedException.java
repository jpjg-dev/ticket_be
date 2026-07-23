package com.jipi.ticket_ledger.payment.application.port.out;

public class PaymentGatewayRejectedException extends PaymentGatewayException {

    public PaymentGatewayRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
