package com.jipi.ticket_ledger.payment.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PaymentOutboxAdminStore {

    Optional<Long> requeueHoldManual(UUID eventId, Long adminUserId, String reason, Instant requeuedAt);

    Optional<String> findStatus(UUID eventId);
}
