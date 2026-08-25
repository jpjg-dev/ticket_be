package com.jipi.ticket_ledger.payment.infrastructure.outbox;

import com.jipi.ticket_ledger.payment.application.port.out.PaymentOutboxMaintenanceStore;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class JdbcPaymentOutboxMaintenanceStore implements PaymentOutboxMaintenanceStore {

    private static final String DELETE_SQL = """
            WITH candidates AS (
                SELECT id
                  FROM payment_outbox_events
                 WHERE status = 'PUBLISHED'
                   AND published_at < :cutoff
                   AND payload_hash IS NOT NULL
                   AND EXISTS (
                       SELECT 1
                         FROM payment_event_audit audit
                        WHERE audit.event_id = payment_outbox_events.event_id
                          AND audit.payload_hash = payment_outbox_events.payload_hash
                   )
                 ORDER BY id
                 FOR UPDATE SKIP LOCKED
                 LIMIT :batchSize
            )
            DELETE FROM payment_outbox_events event
             USING candidates
             WHERE event.id = candidates.id
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public int deletePublishedBefore(Instant cutoff, int batchSize) {
        return jdbcTemplate.update(
                DELETE_SQL,
                new MapSqlParameterSource()
                        .addValue("cutoff", Timestamp.from(cutoff))
                        .addValue("batchSize", batchSize)
        );
    }
}
