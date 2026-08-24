package com.jipi.ticket_ledger.payment.application.port.out;

import com.jipi.ticket_ledger.payment.application.outbox.ClaimedPaymentOutboxEvent;
import com.jipi.ticket_ledger.payment.application.outbox.PaymentOutboxBacklogSnapshot;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PaymentOutboxRelayStore {

    Optional<ClaimedPaymentOutboxEvent> claimNext(Instant now, Instant claimedUntil, UUID claimToken);

    boolean markPublished(Long outboxId, UUID claimToken, Instant publishedAt);

    boolean scheduleRetry(Long outboxId, UUID claimToken, Instant nextRetryAt, String errorCode);

    boolean holdManual(Long outboxId, UUID claimToken, String errorCode);

    PaymentOutboxBacklogSnapshot getBacklogSnapshot(Instant now);
}
