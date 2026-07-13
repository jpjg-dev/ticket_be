package com.jipi.ticket_ledger.payment.application.recovery;

import com.jipi.ticket_ledger.payment.infrastructure.Resilience4jPaymentGatewayCircuitState;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentRecoverySchedulerTest {

    private final PaymentRecoveryService paymentRecoveryService = mock(PaymentRecoveryService.class);
    private final CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
    private final Resilience4jPaymentGatewayCircuitState circuitState =
            new Resilience4jPaymentGatewayCircuitState(circuitBreakerRegistry);
    private final PaymentRecoveryScheduler scheduler =
            new PaymentRecoveryScheduler(paymentRecoveryService, circuitState);

    @BeforeEach
    void setUpSchedulerProperties() {
        ReflectionTestUtils.setField(scheduler, "graceMs", 60_000L);
        ReflectionTestUtils.setField(scheduler, "batchSize", 20);
    }

    @Test
    @DisplayName("lookup breaker OPEN이면 외부 PG 보정 배치를 모두 건너뛰고 backlog gauge만 갱신한다")
    void skipsRecoveryBatchesButUpdatesBacklogWhenLookupCircuitOpen() {
        circuitBreakerRegistry.circuitBreaker("paymentLookup").transitionToForcedOpenState();

        scheduler.recoverGrayZonePayments();

        verify(paymentRecoveryService, never()).reconcileStaleConfirmingPayments(Duration.ofMinutes(1), 20);
        verify(paymentRecoveryService, never()).reconcileStaleCancelingPayments(Duration.ofMinutes(1), 20);
        verify(paymentRecoveryService).updateBacklogGauges();
    }

    @Test
    @DisplayName("lookup breaker HALF_OPEN이면 probe를 위해 보정 배치를 다시 실행한다")
    void resumesRecoveryBatchesWhenLookupCircuitHalfOpen() {
        CircuitBreaker lookupCircuit = circuitBreakerRegistry.circuitBreaker("paymentLookup");
        lookupCircuit.transitionToOpenState();
        lookupCircuit.transitionToHalfOpenState();
        when(paymentRecoveryService.reconcileStaleConfirmingPayments(Duration.ofMinutes(1), 20)).thenReturn(1);
        when(paymentRecoveryService.reconcileStaleCancelingPayments(Duration.ofMinutes(1), 20)).thenReturn(1);

        scheduler.recoverGrayZonePayments();

        verify(paymentRecoveryService).reconcileStaleConfirmingPayments(Duration.ofMinutes(1), 20);
        verify(paymentRecoveryService).reconcileStaleCancelingPayments(Duration.ofMinutes(1), 20);
        verify(paymentRecoveryService).updateBacklogGauges();
    }
}
