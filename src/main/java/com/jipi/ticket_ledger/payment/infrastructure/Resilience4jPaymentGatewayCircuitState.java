package com.jipi.ticket_ledger.payment.infrastructure;

import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayCircuitState;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayTemporarilyUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@Component
public class Resilience4jPaymentGatewayCircuitState implements PaymentGatewayCircuitState {

    private final CircuitBreaker confirmCircuitBreaker;
    private final CircuitBreaker lookupCircuitBreaker;

    public Resilience4jPaymentGatewayCircuitState(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.confirmCircuitBreaker = circuitBreakerRegistry.circuitBreaker(PaymentGatewayCircuitBreakers.CONFIRM);
        this.lookupCircuitBreaker = circuitBreakerRegistry.circuitBreaker(PaymentGatewayCircuitBreakers.LOOKUP);
    }

    @Override
    public ConfirmCallPermit acquireConfirmPermit() {
        try {
            confirmCircuitBreaker.acquirePermission();
            return new Resilience4jConfirmCallPermit(confirmCircuitBreaker);
        } catch (CallNotPermittedException exception) {
            throw unavailable(confirmCircuitBreaker, exception);
        }
    }

    @Override
    public boolean isLookupCircuitOpen() {
        return PaymentGatewayCircuitBreakers.isOpen(lookupCircuitBreaker);
    }

    private PaymentGatewayTemporarilyUnavailableException unavailable(
            CircuitBreaker circuitBreaker,
            CallNotPermittedException cause
    ) {
        long waitMillis = circuitBreaker.getCircuitBreakerConfig()
                .getWaitIntervalFunctionInOpenState()
                .apply(1);
        long retryAfterSeconds = Math.max(1, Duration.ofMillis(waitMillis).toSeconds());
        return new PaymentGatewayTemporarilyUnavailableException(
                "PG 승인 요청을 일시적으로 처리할 수 없습니다.", retryAfterSeconds, cause);
    }

    private static final class Resilience4jConfirmCallPermit implements ConfirmCallPermit {

        private final CircuitBreaker circuitBreaker;
        private final AtomicBoolean completed = new AtomicBoolean();

        private Resilience4jConfirmCallPermit(CircuitBreaker circuitBreaker) {
            this.circuitBreaker = circuitBreaker;
        }

        @Override
        public <T> T execute(Supplier<T> action) {
            if (!completed.compareAndSet(false, true)) {
                throw new IllegalStateException("이미 사용한 PG 승인 permit입니다.");
            }

            long startedAt = System.nanoTime();
            try {
                T result = action.get();
                circuitBreaker.onSuccess(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
                return result;
            } catch (RuntimeException exception) {
                circuitBreaker.onError(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS, exception);
                throw exception;
            }
        }

        @Override
        public void release() {
            if (completed.compareAndSet(false, true)) {
                circuitBreaker.releasePermission();
            }
        }
    }
}
