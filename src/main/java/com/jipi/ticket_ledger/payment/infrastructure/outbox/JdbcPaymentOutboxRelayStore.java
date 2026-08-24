package com.jipi.ticket_ledger.payment.infrastructure.outbox;

import com.jipi.ticket_ledger.payment.application.outbox.ClaimedPaymentOutboxEvent;
import com.jipi.ticket_ledger.payment.application.outbox.PaymentOutboxBacklogSnapshot;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentOutboxRelayStore;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JdbcPaymentOutboxRelayStore implements PaymentOutboxRelayStore {

    private static final String CLAIM_NEXT_SQL = """
            WITH candidate AS (
                SELECT event.id
                FROM payment_outbox_events event
                WHERE event.status = 'PENDING'
                  AND (event.next_retry_at IS NULL OR event.next_retry_at <= :now)
                  AND (event.claimed_until IS NULL OR event.claimed_until <= :now)
                  AND NOT EXISTS (
                      SELECT 1
                      FROM payment_outbox_events prior
                      WHERE prior.payment_id = event.payment_id
                        AND prior.id < event.id
                        AND prior.status <> 'PUBLISHED'
                  )
                ORDER BY event.id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE payment_outbox_events event
               SET claim_token = :claimToken,
                   claimed_until = :claimedUntil
              FROM candidate
             WHERE event.id = candidate.id
            RETURNING event.id,
                      event.event_id,
                      event.payment_id,
                      event.payload::text AS payload,
                      event.retry_count
            """;

    private static final String MARK_PUBLISHED_SQL = """
            UPDATE payment_outbox_events
               SET status = 'PUBLISHED',
                   published_at = :publishedAt,
                   next_retry_at = NULL,
                   last_error = NULL,
                   claim_token = NULL,
                   claimed_until = NULL
             WHERE id = :outboxId
               AND status = 'PENDING'
               AND claim_token = :claimToken
            """;

    private static final String SCHEDULE_RETRY_SQL = """
            UPDATE payment_outbox_events
               SET retry_count = retry_count + 1,
                   next_retry_at = :nextRetryAt,
                   last_error = :errorCode,
                   claim_token = NULL,
                   claimed_until = NULL
             WHERE id = :outboxId
               AND status = 'PENDING'
               AND claim_token = :claimToken
            """;

    private static final String HOLD_MANUAL_SQL = """
            UPDATE payment_outbox_events
               SET status = 'HOLD_MANUAL',
                   retry_count = retry_count + 1,
                   next_retry_at = NULL,
                   last_error = :errorCode,
                   claim_token = NULL,
                   claimed_until = NULL
             WHERE id = :outboxId
               AND status = 'PENDING'
               AND claim_token = :claimToken
            """;

    private static final String BACKLOG_SQL = """
            SELECT COUNT(*) FILTER (WHERE status = 'PENDING') AS pending_count,
                   COUNT(*) FILTER (WHERE status = 'HOLD_MANUAL') AS hold_manual_count,
                   COUNT(*) FILTER (
                       WHERE status = 'PENDING'
                         AND claim_token IS NOT NULL
                         AND claimed_until > :now
                   ) AS active_lease_count,
                   COALESCE(
                       EXTRACT(EPOCH FROM (
                           :now - MIN(created_at) FILTER (WHERE status = 'PENDING')
                       )),
                       0
                   ) AS oldest_pending_age_seconds
              FROM payment_outbox_events
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public Optional<ClaimedPaymentOutboxEvent> claimNext(
            Instant now,
            Instant claimedUntil,
            UUID claimToken
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now))
                .addValue("claimedUntil", Timestamp.from(claimedUntil))
                .addValue("claimToken", claimToken);
        List<ClaimedPaymentOutboxEvent> events = jdbcTemplate.query(
                CLAIM_NEXT_SQL,
                parameters,
                (resultSet, rowNumber) -> toClaimedEvent(resultSet, claimToken)
        );
        return events.stream().findFirst();
    }

    @Override
    @Transactional
    public boolean markPublished(Long outboxId, UUID claimToken, Instant publishedAt) {
        MapSqlParameterSource parameters = claimParameters(outboxId, claimToken)
                .addValue("publishedAt", Timestamp.from(publishedAt));
        return jdbcTemplate.update(MARK_PUBLISHED_SQL, parameters) == 1;
    }

    @Override
    @Transactional
    public boolean scheduleRetry(
            Long outboxId,
            UUID claimToken,
            Instant nextRetryAt,
            String errorCode
    ) {
        MapSqlParameterSource parameters = claimParameters(outboxId, claimToken)
                .addValue("nextRetryAt", Timestamp.from(nextRetryAt))
                .addValue("errorCode", errorCode);
        return jdbcTemplate.update(SCHEDULE_RETRY_SQL, parameters) == 1;
    }

    @Override
    @Transactional
    public boolean holdManual(Long outboxId, UUID claimToken, String errorCode) {
        MapSqlParameterSource parameters = claimParameters(outboxId, claimToken)
                .addValue("errorCode", errorCode);
        return jdbcTemplate.update(HOLD_MANUAL_SQL, parameters) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentOutboxBacklogSnapshot getBacklogSnapshot(Instant now) {
        return jdbcTemplate.queryForObject(
                BACKLOG_SQL,
                new MapSqlParameterSource("now", Timestamp.from(now)),
                (resultSet, rowNumber) -> new PaymentOutboxBacklogSnapshot(
                        resultSet.getLong("pending_count"),
                        resultSet.getLong("hold_manual_count"),
                        resultSet.getLong("active_lease_count"),
                        resultSet.getLong("oldest_pending_age_seconds")
                )
        );
    }

    private ClaimedPaymentOutboxEvent toClaimedEvent(ResultSet resultSet, UUID claimToken) throws SQLException {
        return new ClaimedPaymentOutboxEvent(
                resultSet.getLong("id"),
                resultSet.getObject("event_id", UUID.class),
                resultSet.getLong("payment_id"),
                resultSet.getString("payload"),
                resultSet.getInt("retry_count"),
                claimToken
        );
    }

    private MapSqlParameterSource claimParameters(Long outboxId, UUID claimToken) {
        return new MapSqlParameterSource()
                .addValue("outboxId", outboxId)
                .addValue("claimToken", claimToken);
    }
}
