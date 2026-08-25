package com.jipi.ticket_ledger.paymentaudit.application.maintenance;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentOutboxMaintenanceStore;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.infrastructure.outbox.PaymentOutboxEvent;
import com.jipi.ticket_ledger.payment.infrastructure.outbox.PaymentOutboxEventRepository;
import com.jipi.ticket_ledger.paymentaudit.application.port.out.PaymentAuditInboxMaintenanceStore;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupRepository;
import com.jipi.ticket_ledger.support.PostgresTestContainerSupport;
import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=OFF"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentEventRetentionIntegrationTest extends PostgresTestContainerSupport {

    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

    @Autowired
    private PaymentOutboxMaintenanceStore outboxMaintenanceStore;

    @Autowired
    private PaymentAuditInboxMaintenanceStore inboxMaintenanceStore;

    @Autowired
    private PaymentOutboxEventRepository outboxRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ReservationGroupRepository reservationGroupRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> paymentIds = new ArrayList<>();
    private final List<Long> groupIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM payment_event_audit");
        jdbcTemplate.update("DELETE FROM payment_event_inbox");
        outboxRepository.deleteAllInBatch();
        paymentIds.forEach(paymentRepository::deleteById);
        groupIds.forEach(reservationGroupRepository::deleteById);
        userIds.forEach(userRepository::deleteById);
    }

    @Test
    void cleanupDeletesOnlyPublishedOutboxBeforeCutoffWithinBatch() {
        Payment payment = payment();
        PaymentOutboxEvent oldPublished = outbox(payment, "PaymentApproved");
        PaymentOutboxEvent newPublished = outbox(payment, "PaymentCanceled");
        jdbcTemplate.update(
                "UPDATE payment_outbox_events SET status='PUBLISHED', published_at=? WHERE id=?",
                Timestamp.from(NOW.minusSeconds(1000)),
                oldPublished.getId()
        );
        jdbcTemplate.update(
                "UPDATE payment_outbox_events SET status='PUBLISHED', published_at=? WHERE id=?",
                Timestamp.from(NOW),
                newPublished.getId()
        );
        insertAudit(oldPublished, oldPublished.getPayloadHash());

        int deleted = outboxMaintenanceStore.deletePublishedBefore(NOW.minusSeconds(100), 1);

        assertEquals(1, deleted);
        assertEquals(1, outboxRepository.count());
        assertEquals(newPublished.getId(), outboxRepository.findAll().getFirst().getId());
    }

    @Test
    void cleanupKeepsPublishedOutboxWithoutMatchingAuditEvidence() {
        Payment payment = payment();
        PaymentOutboxEvent event = outbox(payment, "PaymentApproved");
        jdbcTemplate.update(
                "UPDATE payment_outbox_events SET status='PUBLISHED', published_at=? WHERE id=?",
                Timestamp.from(NOW.minusSeconds(1000)),
                event.getId()
        );

        assertEquals(0, outboxMaintenanceStore.deletePublishedBefore(NOW.minusSeconds(100), 10));

        insertAudit(event, "f".repeat(64));
        assertEquals(0, outboxMaintenanceStore.deletePublishedBefore(NOW.minusSeconds(100), 10));
        assertEquals(1, outboxRepository.count());
    }

    @Test
    void cleanupDeletesOnlyInboxBeforeCutoffWithinBatch() {
        insertInbox(UUID.randomUUID(), 0, NOW.minusSeconds(1000));
        insertInbox(UUID.randomUUID(), 1, NOW);

        int deleted = inboxMaintenanceStore.deleteProcessedBefore(NOW.minusSeconds(100), 1);

        assertEquals(1, deleted);
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_event_inbox", Integer.class));
    }

    private void insertInbox(UUID eventId, long offset, Instant processedAt) {
        jdbcTemplate.update("""
                        INSERT INTO payment_event_inbox (
                            event_id, event_type, event_version, payment_id, payload_hash,
                            topic, record_partition, record_offset, received_at, processed_at
                        ) VALUES (?, 'PaymentApproved', 1, 1, ?, 'ticketledger-payment-events', 0, ?, ?, ?)
                        """,
                eventId,
                "0".repeat(64),
                offset,
                Timestamp.from(processedAt),
                Timestamp.from(processedAt)
        );
    }

    private Payment payment() {
        String suffix = String.valueOf(System.nanoTime());
        User user = userRepository.save(new User(
                "retention-" + suffix + "@test.com",
                "password",
                "Retention",
                NOW
        ));
        userIds.add(user.getId());
        ReservationGroup group = reservationGroupRepository.save(
                new ReservationGroup(user, NOW, NOW.plusSeconds(300))
        );
        groupIds.add(group.getId());
        Payment payment = paymentRepository.save(new Payment(
                group,
                10000,
                NOW,
                "retention-" + suffix,
                "KRW"
        ));
        paymentIds.add(payment.getId());
        return payment;
    }

    private PaymentOutboxEvent outbox(Payment payment, String eventType) {
        UUID eventId = UUID.randomUUID();
        String payloadHash = com.jipi.ticket_ledger.global.crypto.Sha256Hasher.hash(
                "{\"eventId\":\"" + eventId + "\"}"
        );
        return outboxRepository.saveAndFlush(PaymentOutboxEvent.pending(
                eventId,
                payment.getId(),
                eventType,
                1,
                JsonNodeFactory.instance.objectNode().put("eventId", eventId.toString()),
                payloadHash,
                NOW,
                NOW
        ));
    }

    private void insertAudit(PaymentOutboxEvent event, String payloadHash) {
        jdbcTemplate.update("""
                        INSERT INTO payment_event_audit (
                            event_id, event_type, event_version, payment_id, order_id,
                            reservation_group_id, user_id, total_amount, currency,
                            occurred_at, source, payload_hash, recorded_at
                        ) VALUES (?, ?, 1, ?, 'order-retention', 1, 1, 11000, 'KRW', ?, 'NORMAL', ?, ?)
                        """,
                event.getEventId(),
                event.getEventType(),
                event.getPaymentId(),
                Timestamp.from(NOW),
                payloadHash,
                Timestamp.from(NOW)
        );
    }
}
