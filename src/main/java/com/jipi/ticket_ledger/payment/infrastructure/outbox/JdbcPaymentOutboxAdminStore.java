package com.jipi.ticket_ledger.payment.infrastructure.outbox;

import com.jipi.ticket_ledger.payment.application.port.out.PaymentOutboxAdminStore;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.sql.Timestamp;

@Component
@RequiredArgsConstructor
public class JdbcPaymentOutboxAdminStore implements PaymentOutboxAdminStore {

    private static final String REQUEUE_SQL = """
            UPDATE payment_outbox_events
               SET status = 'PENDING',
                   retry_count = 0,
                   next_retry_at = NULL,
                   last_error = NULL,
                   claim_token = NULL,
                   claimed_until = NULL
             WHERE event_id = :eventId
               AND status = 'HOLD_MANUAL'
            RETURNING payment_id
            """;

    private static final String FIND_STATUS_SQL = """
            SELECT status
              FROM payment_outbox_events
             WHERE event_id = :eventId
            """;

    private static final String INSERT_REQUEUE_AUDIT_SQL = """
            INSERT INTO payment_outbox_requeue_audit (
                event_id, payment_id, admin_user_id, reason, requeued_at
            ) VALUES (
                :eventId, :paymentId, :adminUserId, :reason, :requeuedAt
            )
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public Optional<Long> requeueHoldManual(
            UUID eventId,
            Long adminUserId,
            String reason,
            Instant requeuedAt
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("eventId", eventId)
                .addValue("adminUserId", adminUserId)
                .addValue("reason", reason)
                .addValue("requeuedAt", Timestamp.from(requeuedAt));
        List<Long> paymentIds = jdbcTemplate.queryForList(
                REQUEUE_SQL,
                parameters,
                Long.class
        );
        Optional<Long> paymentId = paymentIds.stream().findFirst();
        paymentId.ifPresent(value -> jdbcTemplate.update(
                INSERT_REQUEUE_AUDIT_SQL,
                parameters.addValue("paymentId", value)
        ));
        return paymentId;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findStatus(UUID eventId) {
        List<String> statuses = jdbcTemplate.queryForList(
                FIND_STATUS_SQL,
                new MapSqlParameterSource("eventId", eventId),
                String.class
        );
        return statuses.stream().findFirst();
    }
}
