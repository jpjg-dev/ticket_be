package com.jipi.ticket_ledger.payment.application.outbox;

import com.jipi.ticket_ledger.payment.application.port.out.PaymentEventPublishFailureKind;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentOutboxRetryPolicyTest {

    private final PaymentOutboxRelayProperties properties = properties();
    private final PaymentOutboxRetryPolicy retryPolicy = new PaymentOutboxRetryPolicy(properties);

    @Test
    void exponentialBackoffIsCapped() {
        assertEquals(Duration.ofSeconds(5), retryPolicy.backoff(1));
        assertEquals(Duration.ofSeconds(10), retryPolicy.backoff(2));
        assertEquals(Duration.ofMinutes(5), retryPolicy.backoff(20));
    }

    @Test
    void poisonIsHeldImmediatelyButTransientKeepsRetrying() {
        assertTrue(retryPolicy.shouldHold(PaymentEventPublishFailureKind.POISON, 1));
        assertFalse(retryPolicy.shouldHold(PaymentEventPublishFailureKind.TRANSIENT, 100));
    }

    @Test
    void unknownIsHeldAtConfiguredAttemptLimit() {
        assertFalse(retryPolicy.shouldHold(PaymentEventPublishFailureKind.UNKNOWN, 9));
        assertTrue(retryPolicy.shouldHold(PaymentEventPublishFailureKind.UNKNOWN, 10));
    }

    @Test
    void leaseMustBeLongerThanSendTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new PaymentOutboxRelayProperties(
                true,
                Duration.ofSeconds(1),
                20,
                Duration.ofSeconds(10),
                Duration.ofSeconds(10),
                Duration.ofSeconds(5),
                Duration.ofMinutes(5),
                10,
                "ticketledger-payment-events",
                3,
                Duration.ofDays(7)
        ));
    }

    private PaymentOutboxRelayProperties properties() {
        return new PaymentOutboxRelayProperties(
                true,
                Duration.ofSeconds(1),
                20,
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                Duration.ofSeconds(5),
                Duration.ofMinutes(5),
                10,
                "ticketledger-payment-events",
                3,
                Duration.ofDays(7)
        );
    }
}
