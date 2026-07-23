package com.jipi.ticket_ledger.payment.application.port.out;

import java.util.function.Supplier;

public interface PaymentGatewayCircuitState {

    ConfirmCallPermit acquireConfirmPermit();

    boolean isLookupCircuitOpen();

    interface ConfirmCallPermit {

        <T> T execute(Supplier<T> action);

        void release();
    }
}
