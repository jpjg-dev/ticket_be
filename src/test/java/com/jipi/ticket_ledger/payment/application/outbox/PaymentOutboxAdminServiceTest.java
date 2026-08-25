package com.jipi.ticket_ledger.payment.application.outbox;

import com.jipi.ticket_ledger.payment.application.observability.PaymentOutboxMetrics;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentOutboxAdminStore;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentOutboxAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

    @Mock
    private PaymentOutboxAdminStore store;

    @Mock
    private PaymentOutboxMetrics metrics;

    @Test
    void holdManualEventCanBeRequeued() {
        UUID eventId = UUID.randomUUID();
        when(store.requeueHoldManual(eventId, 1L, "payload fixed", NOW)).thenReturn(Optional.of(10L));
        PaymentOutboxAdminService service = service();

        PaymentOutboxRequeueResult result = service.requeue(eventId, 1L, "payload fixed");

        assertEquals("PENDING", result.status());
        assertEquals(10L, result.paymentId());
        verify(metrics).recordRequeue("success");
    }

    @Test
    void nonHoldEventIsRejected() {
        UUID eventId = UUID.randomUUID();
        when(store.requeueHoldManual(eventId, 1L, "retry", NOW)).thenReturn(Optional.empty());
        when(store.findStatus(eventId)).thenReturn(Optional.of("PUBLISHED"));
        PaymentOutboxAdminService service = service();

        assertThrows(IllegalStateException.class, () -> service.requeue(eventId, 1L, "retry"));
        verify(metrics).recordRequeue("rejected");
    }

    @Test
    void missingEventReturnsNotFound() {
        UUID eventId = UUID.randomUUID();
        when(store.requeueHoldManual(eventId, 1L, "retry", NOW)).thenReturn(Optional.empty());
        when(store.findStatus(eventId)).thenReturn(Optional.empty());
        PaymentOutboxAdminService service = service();

        assertThrows(EntityNotFoundException.class, () -> service.requeue(eventId, 1L, "retry"));
        verify(metrics).recordRequeue("not_found");
    }

    private PaymentOutboxAdminService service() {
        return new PaymentOutboxAdminService(
                store,
                metrics,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
