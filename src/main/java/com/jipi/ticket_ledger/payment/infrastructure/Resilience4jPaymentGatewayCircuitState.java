package com.jipi.ticket_ledger.payment.infrastructure;

import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayCircuitState;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.stereotype.Component;

@Component
public class Resilience4jPaymentGatewayCircuitState implements PaymentGatewayCircuitState {

    private final CircuitBreaker confirmCircuitBreaker;
    private final CircuitBreaker lookupCircuitBreaker;

    public Resilience4jPaymentGatewayCircuitState(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.confirmCircuitBreaker = circuitBreakerRegistry.circuitBreaker(PaymentGatewayCircuitBreakers.CONFIRM);
        this.lookupCircuitBreaker = circuitBreakerRegistry.circuitBreaker(PaymentGatewayCircuitBreakers.LOOKUP);
    }

    @Override
    public boolean isConfirmCircuitOpen() {
        return PaymentGatewayCircuitBreakers.isOpen(confirmCircuitBreaker);
    }

    @Override
    public boolean isLookupCircuitOpen() {
        return PaymentGatewayCircuitBreakers.isOpen(lookupCircuitBreaker);
    }
}
