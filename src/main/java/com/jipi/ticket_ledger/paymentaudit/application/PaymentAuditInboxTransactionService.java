package com.jipi.ticket_ledger.paymentaudit.application;

import com.jipi.ticket_ledger.paymentaudit.application.model.PaymentAuditConsumeResult;
import com.jipi.ticket_ledger.paymentaudit.application.model.PaymentAuditEvent;
import com.jipi.ticket_ledger.paymentaudit.application.model.PaymentAuditRecordMetadata;
import com.jipi.ticket_ledger.paymentaudit.application.model.StoredEventFingerprint;
import com.jipi.ticket_ledger.paymentaudit.application.port.out.PaymentAuditInboxStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PaymentAuditInboxTransactionService {

    private final PaymentAuditInboxStore paymentAuditInboxStore;
    private final PaymentAuditEventCodec paymentAuditEventCodec;
    private final PaymentAuditInboxProperties properties;
    private final Clock clock;

    @Transactional
    public PaymentAuditConsumeResult consume(
            String messageKey,
            String payload,
            PaymentAuditRecordMetadata metadata
    ) {
        PaymentAuditEventCodec.DecodedPaymentAuditEvent decoded = paymentAuditEventCodec.decode(payload);
        PaymentAuditEvent event = decoded.event();
        PaymentAuditEventValidator.validate(properties.topic(), messageKey, metadata.topic(), event);
        String payloadHash = decoded.payloadHash();
        Instant processedAt = clock.instant();

        Optional<StoredEventFingerprint> existingAudit =
                paymentAuditInboxStore.findAuditByEventId(event.eventId());
        if (existingAudit.isPresent()) {
            verifySamePayload(event.eventId().toString(), payloadHash, existingAudit.get());
            ensureInbox(event, payloadHash, metadata, processedAt);
            return PaymentAuditConsumeResult.duplicate(event.eventType());
        }

        if (!paymentAuditInboxStore.insertInboxIfAbsent(event, payloadHash, metadata, processedAt)) {
            verifyExistingInbox(event, payloadHash, metadata);
            return PaymentAuditConsumeResult.duplicate(event.eventType());
        }

        paymentAuditInboxStore.insertAudit(event, payloadHash, processedAt);
        return PaymentAuditConsumeResult.processed(event.eventType());
    }

    private void ensureInbox(
            PaymentAuditEvent event,
            String payloadHash,
            PaymentAuditRecordMetadata metadata,
            Instant processedAt
    ) {
        if (!paymentAuditInboxStore.insertInboxIfAbsent(event, payloadHash, metadata, processedAt)) {
            verifyExistingInbox(event, payloadHash, metadata);
        }
    }

    private void verifyExistingInbox(
            PaymentAuditEvent event,
            String payloadHash,
            PaymentAuditRecordMetadata metadata
    ) {
        Optional<StoredEventFingerprint> byEventId =
                paymentAuditInboxStore.findInboxByEventId(event.eventId());
        if (byEventId.isPresent()) {
            verifySamePayload(event.eventId().toString(), payloadHash, byEventId.get());
            return;
        }

        StoredEventFingerprint byRecord = paymentAuditInboxStore.findInboxByRecord(
                        metadata.topic(),
                        metadata.partition(),
                        metadata.offset()
                )
                .orElseThrow(() -> new PaymentInboxPayloadConflictException(
                        "Inbox unique conflict가 발생했지만 기존 레코드를 찾을 수 없습니다."
                ));
        if (!event.eventId().equals(byRecord.eventId())) {
            throw new PaymentInboxPayloadConflictException(
                    "같은 Kafka record 위치에 다른 eventId가 저장되어 있습니다."
            );
        }
        verifySamePayload(event.eventId().toString(), payloadHash, byRecord);
    }

    private void verifySamePayload(
            String eventId,
            String payloadHash,
            StoredEventFingerprint existing
    ) {
        if (!payloadHash.equals(existing.payloadHash())) {
            throw new PaymentInboxPayloadConflictException(
                    "같은 eventId에 서로 다른 payload가 전달되었습니다. eventId=" + eventId
            );
        }
    }
}
