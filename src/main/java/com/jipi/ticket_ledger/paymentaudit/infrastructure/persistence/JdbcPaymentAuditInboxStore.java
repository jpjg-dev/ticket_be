package com.jipi.ticket_ledger.paymentaudit.infrastructure.persistence;

import com.jipi.ticket_ledger.paymentaudit.application.model.PaymentAuditEvent;
import com.jipi.ticket_ledger.paymentaudit.application.model.PaymentAuditRecordMetadata;
import com.jipi.ticket_ledger.paymentaudit.application.model.StoredEventFingerprint;
import com.jipi.ticket_ledger.paymentaudit.application.port.out.PaymentAuditInboxStore;
import com.jipi.ticket_ledger.paymentaudit.application.port.out.PaymentAuditInboxMaintenanceStore;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JdbcPaymentAuditInboxStore implements PaymentAuditInboxStore, PaymentAuditInboxMaintenanceStore {

    private static final String FIND_AUDIT_SQL = """
            SELECT event_id, payload_hash
              FROM payment_event_audit
             WHERE event_id = :eventId
            """;

    private static final String INSERT_INBOX_SQL = """
            INSERT INTO payment_event_inbox (
                event_id, event_type, event_version, payment_id, payload_hash,
                topic, record_partition, record_offset, received_at, processed_at
            ) VALUES (
                :eventId, :eventType, :eventVersion, :paymentId, :payloadHash,
                :topic, :partition, :offset, :receivedAt, :processedAt
            )
            ON CONFLICT DO NOTHING
            RETURNING id
            """;

    private static final String FIND_INBOX_BY_EVENT_SQL = """
            SELECT event_id, payload_hash
              FROM payment_event_inbox
             WHERE event_id = :eventId
            """;

    private static final String FIND_INBOX_BY_RECORD_SQL = """
            SELECT event_id, payload_hash
              FROM payment_event_inbox
             WHERE topic = :topic
               AND record_partition = :partition
               AND record_offset = :offset
            """;

    private static final String INSERT_AUDIT_SQL = """
            INSERT INTO payment_event_audit (
                event_id, event_type, event_version, payment_id, order_id,
                reservation_group_id, user_id, total_amount, currency,
                occurred_at, source, payload_hash, recorded_at
            ) VALUES (
                :eventId, :eventType, :eventVersion, :paymentId, :orderId,
                :reservationGroupId, :userId, :totalAmount, :currency,
                :occurredAt, :source, :payloadHash, :recordedAt
            )
            """;

    private static final String DELETE_INBOX_SQL = """
            WITH candidates AS (
                SELECT id
                  FROM payment_event_inbox
                 WHERE processed_at < :cutoff
                 ORDER BY id
                 FOR UPDATE SKIP LOCKED
                 LIMIT :batchSize
            )
            DELETE FROM payment_event_inbox inbox
             USING candidates
             WHERE inbox.id = candidates.id
            """;

    private static final String RETENTION_SNAPSHOT_SQL = """
            SELECT COUNT(*) AS record_count,
                   COALESCE(EXTRACT(EPOCH FROM (:now - MIN(processed_at))), 0) AS oldest_age_seconds
              FROM payment_event_inbox
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public Optional<StoredEventFingerprint> findAuditByEventId(UUID eventId) {
        return findOne(FIND_AUDIT_SQL, new MapSqlParameterSource("eventId", eventId));
    }

    @Override
    public boolean insertInboxIfAbsent(
            PaymentAuditEvent event,
            String payloadHash,
            PaymentAuditRecordMetadata metadata,
            Instant processedAt
    ) {
        MapSqlParameterSource parameters = eventParameters(event, payloadHash)
                .addValue("topic", metadata.topic())
                .addValue("partition", metadata.partition())
                .addValue("offset", metadata.offset())
                .addValue("receivedAt", Timestamp.from(metadata.receivedAt()))
                .addValue("processedAt", Timestamp.from(processedAt));
        return !jdbcTemplate.queryForList(INSERT_INBOX_SQL, parameters, Long.class).isEmpty();
    }

    @Override
    public Optional<StoredEventFingerprint> findInboxByEventId(UUID eventId) {
        return findOne(FIND_INBOX_BY_EVENT_SQL, new MapSqlParameterSource("eventId", eventId));
    }

    @Override
    public Optional<StoredEventFingerprint> findInboxByRecord(String topic, int partition, long offset) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("topic", topic)
                .addValue("partition", partition)
                .addValue("offset", offset);
        return findOne(FIND_INBOX_BY_RECORD_SQL, parameters);
    }

    @Override
    public void insertAudit(PaymentAuditEvent event, String payloadHash, Instant recordedAt) {
        MapSqlParameterSource parameters = eventParameters(event, payloadHash)
                .addValue("orderId", event.orderId())
                .addValue("reservationGroupId", event.reservationGroupId())
                .addValue("userId", event.userId())
                .addValue("totalAmount", event.totalAmount())
                .addValue("currency", event.currency())
                .addValue("occurredAt", Timestamp.from(event.occurredAt()))
                .addValue("source", event.source())
                .addValue("recordedAt", Timestamp.from(recordedAt));
        jdbcTemplate.update(INSERT_AUDIT_SQL, parameters);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public int deleteProcessedBefore(Instant cutoff, int batchSize) {
        return jdbcTemplate.update(
                DELETE_INBOX_SQL,
                new MapSqlParameterSource()
                        .addValue("cutoff", Timestamp.from(cutoff))
                        .addValue("batchSize", batchSize)
        );
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public InboxRetentionSnapshot getRetentionSnapshot(Instant now) {
        return jdbcTemplate.queryForObject(
                RETENTION_SNAPSHOT_SQL,
                new MapSqlParameterSource("now", Timestamp.from(now)),
                (resultSet, rowNumber) -> new InboxRetentionSnapshot(
                        resultSet.getLong("record_count"),
                        resultSet.getLong("oldest_age_seconds")
                )
        );
    }

    private Optional<StoredEventFingerprint> findOne(String sql, MapSqlParameterSource parameters) {
        List<StoredEventFingerprint> results = jdbcTemplate.query(
                sql,
                parameters,
                (resultSet, rowNumber) -> new StoredEventFingerprint(
                        resultSet.getObject("event_id", UUID.class),
                        resultSet.getString("payload_hash").trim()
                )
        );
        return results.stream().findFirst();
    }

    private MapSqlParameterSource eventParameters(PaymentAuditEvent event, String payloadHash) {
        return new MapSqlParameterSource()
                .addValue("eventId", event.eventId())
                .addValue("eventType", event.eventType())
                .addValue("eventVersion", event.eventVersion())
                .addValue("paymentId", event.paymentId())
                .addValue("payloadHash", payloadHash);
    }
}
