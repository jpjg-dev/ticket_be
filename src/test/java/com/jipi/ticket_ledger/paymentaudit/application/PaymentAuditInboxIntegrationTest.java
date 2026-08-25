package com.jipi.ticket_ledger.paymentaudit.application;

import com.jipi.ticket_ledger.paymentaudit.application.model.PaymentAuditConsumeResult;
import com.jipi.ticket_ledger.paymentaudit.application.model.PaymentAuditRecordMetadata;
import com.jipi.ticket_ledger.support.PostgresTestContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=OFF"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentAuditInboxIntegrationTest extends PostgresTestContainerSupport {

    @Autowired
    private PaymentAuditInboxTransactionService transactionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM payment_event_audit");
        jdbcTemplate.update("DELETE FROM payment_event_inbox");
    }

    @Test
    void firstDeliveryCreatesInboxAndAuditAndSemanticDuplicateIsNoop() {
        UUID eventId = UUID.randomUUID();
        String first = payload(eventId, 11000);
        String reordered = reorderedPayload(eventId, 11000);

        PaymentAuditConsumeResult processed = transactionService.consume(
                "1", first, metadata(0)
        );
        PaymentAuditConsumeResult duplicate = transactionService.consume(
                "1", reordered, metadata(1)
        );

        assertEquals(PaymentAuditConsumeResult.Outcome.PROCESSED, processed.outcome());
        assertEquals(PaymentAuditConsumeResult.Outcome.DUPLICATE, duplicate.outcome());
        assertEquals(1, count("payment_event_inbox"));
        assertEquals(1, count("payment_event_audit"));
    }

    @Test
    void sameEventIdWithDifferentPayloadIsRejectedWithoutChangingOriginalAudit() {
        UUID eventId = UUID.randomUUID();
        transactionService.consume("1", payload(eventId, 11000), metadata(0));

        assertThrows(
                PaymentInboxPayloadConflictException.class,
                () -> transactionService.consume("1", payload(eventId, 22000), metadata(1))
        );

        assertEquals(1, count("payment_event_inbox"));
        assertEquals(1, count("payment_event_audit"));
        assertEquals(11000, jdbcTemplate.queryForObject(
                "SELECT total_amount FROM payment_event_audit WHERE event_id = ?",
                Integer.class,
                eventId
        ));
    }

    private PaymentAuditRecordMetadata metadata(long offset) {
        return new PaymentAuditRecordMetadata(
                "ticketledger-payment-events",
                0,
                offset,
                Instant.parse("2026-08-25T00:00:00Z")
        );
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private String payload(UUID eventId, int totalAmount) {
        return """
                {"eventId":"%s","eventType":"PaymentApproved","eventVersion":1,"paymentId":1,"orderId":"order-1","reservationGroupId":2,"userId":3,"totalAmount":%d,"currency":"KRW","occurredAt":"2026-08-25T00:00:00Z","source":"NORMAL"}
                """.formatted(eventId, totalAmount);
    }

    private String reorderedPayload(UUID eventId, int totalAmount) {
        return """
                {"source":"NORMAL","currency":"KRW","totalAmount":%d,"userId":3,"reservationGroupId":2,"orderId":"order-1","paymentId":1,"eventVersion":1,"eventType":"PaymentApproved","occurredAt":"2026-08-25T00:00:00Z","eventId":"%s"}
                """.formatted(totalAmount, eventId);
    }
}
