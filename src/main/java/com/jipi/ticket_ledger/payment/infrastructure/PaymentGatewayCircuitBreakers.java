package com.jipi.ticket_ledger.payment.infrastructure;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

final class PaymentGatewayCircuitBreakers {

    static final String CONFIRM = "paymentConfirm";
    static final String LOOKUP = "paymentLookup";
    static final String CANCEL = "paymentCancel";

    private PaymentGatewayCircuitBreakers() {
    }

    static boolean isOpen(CircuitBreaker circuitBreaker) {
        CircuitBreaker.State state = circuitBreaker.getState();
        return state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN;
    }
}
