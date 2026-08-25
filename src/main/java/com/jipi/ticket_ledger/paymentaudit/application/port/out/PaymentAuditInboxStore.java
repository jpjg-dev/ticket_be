package com.jipi.ticket_ledger.paymentaudit.application.port.out;

import com.jipi.ticket_ledger.paymentaudit.application.model.PaymentAuditEvent;
import com.jipi.ticket_ledger.paymentaudit.application.model.PaymentAuditRecordMetadata;
import com.jipi.ticket_ledger.paymentaudit.application.model.StoredEventFingerprint;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAuditInboxStore {

    Optional<StoredEventFingerprint> findAuditByEventId(UUID eventId);

    boolean insertInboxIfAbsent(
            PaymentAuditEvent event,
            String payloadHash,
            PaymentAuditRecordMetadata metadata,
            Instant processedAt
    );

    Optional<StoredEventFingerprint> findInboxByEventId(UUID eventId);

    Optional<StoredEventFingerprint> findInboxByRecord(String topic, int partition, long offset);

    void insertAudit(PaymentAuditEvent event, String payloadHash, Instant recordedAt);
}
