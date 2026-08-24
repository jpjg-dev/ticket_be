package com.jipi.ticket_ledger.payment.application.outbox;

import com.jipi.ticket_ledger.payment.application.observability.PaymentOutboxMetrics;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentEventPublishException;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentEventPublishFailureKind;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentEventPublisher;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentOutboxRelayStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentOutboxRelayServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    @Mock
    private PaymentOutboxRelayStore store;

    @Mock
    private PaymentEventPublisher publisher;

    @Mock
    private PaymentOutboxMetrics metrics;

    private PaymentOutboxRelayService relayService;

    @BeforeEach
    void setUp() {
        PaymentOutboxRelayProperties properties = properties();
        relayService = new PaymentOutboxRelayService(
                store,
                publisher,
                new PaymentOutboxRetryPolicy(properties),
                properties,
                metrics,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        lenient().when(store.getBacklogSnapshot(NOW))
                .thenReturn(new PaymentOutboxBacklogSnapshot(0, 0, 0, 0));
    }

    @Test
    void poisonPaymentDoesNotBlockAnotherPayment() {
        ClaimedPaymentOutboxEvent poison = event(1L, 100L, 0);
        ClaimedPaymentOutboxEvent healthy = event(2L, 200L, 0);
        when(store.claimNext(eq(NOW), any(), any()))
                .thenReturn(Optional.of(poison))
                .thenReturn(Optional.of(healthy))
                .thenReturn(Optional.empty());
        doThrow(new PaymentEventPublishException(
                PaymentEventPublishFailureKind.POISON,
                "POISON_RecordTooLargeException",
                new RuntimeException("too large")
        )).when(publisher).publish(poison);
        when(store.holdManual(poison.outboxId(), poison.claimToken(), "POISON_RecordTooLargeException"))
                .thenReturn(true);
        when(store.markPublished(healthy.outboxId(), healthy.claimToken(), NOW)).thenReturn(true);

        PaymentOutboxRelayResult result = relayService.publishBatch();

        assertEquals(2, result.claimedCount());
        assertEquals(1, result.publishedCount());
        assertEquals(1, result.holdManualCount());
        verify(publisher).publish(healthy);
    }

    @Test
    void transientFailureIsRescheduledAndAnotherPaymentContinues() {
        ClaimedPaymentOutboxEvent failed = event(1L, 100L, 0);
        ClaimedPaymentOutboxEvent healthy = event(2L, 200L, 0);
        when(store.claimNext(eq(NOW), any(), any()))
                .thenReturn(Optional.of(failed))
                .thenReturn(Optional.of(healthy))
                .thenReturn(Optional.empty());
        doThrow(new PaymentEventPublishException(
                PaymentEventPublishFailureKind.TRANSIENT,
                "TRANSIENT_TimeoutException",
                new RuntimeException("timeout")
        )).when(publisher).publish(failed);
        when(store.scheduleRetry(
                failed.outboxId(),
                failed.claimToken(),
                NOW.plusSeconds(5),
                "TRANSIENT_TimeoutException"
        )).thenReturn(true);
        when(store.markPublished(healthy.outboxId(), healthy.claimToken(), NOW)).thenReturn(true);

        PaymentOutboxRelayResult result = relayService.publishBatch();

        assertEquals(1, result.retryScheduledCount());
        assertEquals(1, result.publishedCount());
        verify(publisher).publish(healthy);
    }

    @Test
    void ackAfterDatabaseFailureLeavesClaimForLeaseRecovery() {
        ClaimedPaymentOutboxEvent event = event(1L, 100L, 0);
        when(store.claimNext(eq(NOW), any(), any())).thenReturn(Optional.of(event));
        when(store.markPublished(event.outboxId(), event.claimToken(), NOW))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class, () -> relayService.publishBatch());

        verify(store, never()).scheduleRetry(any(), any(), any(), any());
        verify(store, never()).holdManual(any(), any(), any());
    }

    private ClaimedPaymentOutboxEvent event(Long outboxId, Long paymentId, int retryCount) {
        return new ClaimedPaymentOutboxEvent(
                outboxId,
                UUID.randomUUID(),
                paymentId,
                "{\"eventId\":\"test\"}",
                retryCount,
                UUID.randomUUID()
        );
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
