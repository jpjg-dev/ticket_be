package com.jipi.ticket_ledger.payment.infrastructure.outbox;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jipi.ticket_ledger.payment.application.outbox.ClaimedPaymentOutboxEvent;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentOutboxRelayStore;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentOutboxAdminStore;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupRepository;
import com.jipi.ticket_ledger.support.PostgresTestContainerSupport;
import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=OFF"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentOutboxRelayStoreIntegrationTest extends PostgresTestContainerSupport {

    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    @Autowired
    private PaymentOutboxRelayStore relayStore;

    @Autowired
    private PaymentOutboxAdminStore adminStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PaymentOutboxEventRepository outboxRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ReservationGroupRepository reservationGroupRepository;

    @Autowired
    private UserRepository userRepository;

    private final List<Long> paymentIds = new ArrayList<>();
    private final List<Long> groupIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM payment_outbox_requeue_audit");
        outboxRepository.deleteAllInBatch();
        paymentIds.forEach(paymentRepository::deleteById);
        groupIds.forEach(reservationGroupRepository::deleteById);
        userIds.forEach(userRepository::deleteById);
    }

    @Test
    void samePaymentNextEventWaitsUntilPreviousEventIsPublished() {
        Payment payment = payment("ordered");
        PaymentOutboxEvent approved = outbox(payment, "PaymentApproved");
        PaymentOutboxEvent canceled = outbox(payment, "PaymentCanceled");

        ClaimedPaymentOutboxEvent first = claim(NOW, UUID.randomUUID()).orElseThrow();
        assertEquals(approved.getId(), first.outboxId());
        assertTrue(claim(NOW, UUID.randomUUID()).isEmpty());

        assertTrue(relayStore.markPublished(first.outboxId(), first.claimToken(), NOW));
        ClaimedPaymentOutboxEvent second = claim(NOW, UUID.randomUUID()).orElseThrow();
        assertEquals(canceled.getId(), second.outboxId());
    }

    @Test
    void holdManualBlocksOnlyItsPayment() {
        Payment blockedPayment = payment("blocked");
        Payment otherPayment = payment("healthy");
        PaymentOutboxEvent blockedFirst = outbox(blockedPayment, "PaymentApproved");
        outbox(blockedPayment, "PaymentCanceled");
        PaymentOutboxEvent healthy = outbox(otherPayment, "PaymentApproved");

        ClaimedPaymentOutboxEvent first = claim(NOW, UUID.randomUUID()).orElseThrow();
        assertEquals(blockedFirst.getId(), first.outboxId());
        assertTrue(relayStore.holdManual(first.outboxId(), first.claimToken(), "POISON_TEST"));

        ClaimedPaymentOutboxEvent next = claim(NOW, UUID.randomUUID()).orElseThrow();
        assertEquals(healthy.getId(), next.outboxId());
        assertEquals(otherPayment.getId(), next.paymentId());
    }

    @Test
    void retryBackoffBlocksOnlyItsPaymentUntilDue() {
        Payment delayedPayment = payment("delayed");
        Payment otherPayment = payment("other");
        PaymentOutboxEvent delayedFirst = outbox(delayedPayment, "PaymentApproved");
        outbox(delayedPayment, "PaymentCanceled");
        PaymentOutboxEvent other = outbox(otherPayment, "PaymentApproved");

        ClaimedPaymentOutboxEvent first = claim(NOW, UUID.randomUUID()).orElseThrow();
        assertEquals(delayedFirst.getId(), first.outboxId());
        assertTrue(relayStore.scheduleRetry(
                first.outboxId(),
                first.claimToken(),
                NOW.plusSeconds(5),
                "TRANSIENT_TEST"
        ));

        ClaimedPaymentOutboxEvent duringBackoff = claim(NOW.plusSeconds(1), UUID.randomUUID()).orElseThrow();
        assertEquals(other.getId(), duringBackoff.outboxId());

        ClaimedPaymentOutboxEvent dueRetry = claim(NOW.plusSeconds(6), UUID.randomUUID()).orElseThrow();
        assertEquals(delayedFirst.getId(), dueRetry.outboxId());
    }

    @Test
    void adminRequeueResetsOnlyHoldManualEvent() {
        Payment payment = payment("requeue");
        PaymentOutboxEvent event = outbox(payment, "PaymentApproved");
        ClaimedPaymentOutboxEvent claimed = claim(NOW, UUID.randomUUID()).orElseThrow();
        assertTrue(relayStore.holdManual(claimed.outboxId(), claimed.claimToken(), "POISON_TEST"));

        assertEquals(payment.getId(), adminStore.requeueHoldManual(
                event.getEventId(),
                1L,
                "payload fixed",
                NOW.plusSeconds(1)
        ).orElseThrow());

        PaymentOutboxEvent requeued = outboxRepository.findByEventId(event.getEventId()).orElseThrow();
        assertEquals(PaymentOutboxStatus.PENDING, requeued.getStatus());
        assertEquals(0, requeued.getRetryCount());
        assertNull(requeued.getLastError());
        assertEquals(1, paymentOutboxRequeueAuditCount(event.getEventId()));
    }

    private int paymentOutboxRequeueAuditCount(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_outbox_requeue_audit WHERE event_id = ?",
                Integer.class,
                eventId
        );
    }

    @Test
    void expiredLeaseCanBeReclaimedAndOldTokenCannotSettle() {
        Payment payment = payment("lease");
        outbox(payment, "PaymentApproved");
        UUID oldToken = UUID.randomUUID();
        ClaimedPaymentOutboxEvent first = claim(NOW, oldToken).orElseThrow();

        assertTrue(claim(NOW.plusSeconds(10), UUID.randomUUID()).isEmpty());

        UUID newToken = UUID.randomUUID();
        ClaimedPaymentOutboxEvent reclaimed = claim(NOW.plusSeconds(31), newToken).orElseThrow();
        assertEquals(first.outboxId(), reclaimed.outboxId());
        assertFalse(relayStore.markPublished(first.outboxId(), oldToken, NOW.plusSeconds(32)));
        assertTrue(relayStore.markPublished(reclaimed.outboxId(), newToken, NOW.plusSeconds(32)));
    }

    @Test
    void concurrentWorkersDoNotClaimSameEvent() throws Exception {
        Payment payment = payment("concurrent");
        outbox(payment, "PaymentApproved");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Optional<ClaimedPaymentOutboxEvent>> first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return claim(NOW, UUID.randomUUID());
            });
            Future<Optional<ClaimedPaymentOutboxEvent>> second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return claim(NOW, UUID.randomUUID());
            });

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            long claimedCount = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
                    .stream()
                    .filter(Optional::isPresent)
                    .count();
            assertEquals(1, claimedCount);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Optional<ClaimedPaymentOutboxEvent> claim(Instant now, UUID token) {
        return relayStore.claimNext(now, now.plusSeconds(30), token);
    }

    private Payment payment(String suffix) {
        User user = userRepository.save(new User(
                "outbox-relay-" + suffix + "-" + System.nanoTime() + "@test.com",
                "password",
                "Outbox Relay",
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
                "outbox-" + suffix + "-" + System.nanoTime(),
                "KRW"
        ));
        paymentIds.add(payment.getId());
        return payment;
    }

    private PaymentOutboxEvent outbox(Payment payment, String eventType) {
        return outboxRepository.saveAndFlush(PaymentOutboxEvent.pending(
                UUID.randomUUID(),
                payment.getId(),
                eventType,
                1,
                JsonNodeFactory.instance.objectNode()
                        .put("eventType", eventType)
                        .put("paymentId", payment.getId()),
                NOW,
                NOW
        ));
    }
}
