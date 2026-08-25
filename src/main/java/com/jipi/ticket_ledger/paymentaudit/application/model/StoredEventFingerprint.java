package com.jipi.ticket_ledger.paymentaudit.application.model;

import java.util.UUID;

public record StoredEventFingerprint(
        UUID eventId,
        String payloadHash
) {
}
