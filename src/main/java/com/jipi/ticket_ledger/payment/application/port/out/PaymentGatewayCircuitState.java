package com.jipi.ticket_ledger.payment.application.port.out;

public interface PaymentGatewayCircuitState {

    boolean isConfirmCircuitOpen();

    boolean isLookupCircuitOpen();
}
