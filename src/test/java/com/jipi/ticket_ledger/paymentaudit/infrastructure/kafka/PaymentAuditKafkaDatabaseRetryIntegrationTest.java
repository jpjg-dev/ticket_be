package com.jipi.ticket_ledger.paymentaudit.infrastructure.kafka;

import com.jipi.ticket_ledger.paymentaudit.application.PaymentAuditInboxTransactionService;
import com.jipi.ticket_ledger.paymentaudit.application.model.PaymentAuditConsumeResult;
import com.jipi.ticket_ledger.support.KafkaPostgresTestContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "app.scheduling.enabled=false",
        "payment.outbox.relay.enabled=true",
        "payment.audit.consumer.enabled=true",
        "payment.audit.consumer.group-id=ticketledger-payment-audit-db-retry-test",
        "payment.audit.consumer.database-retry-delay=100ms"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentAuditKafkaDatabaseRetryIntegrationTest extends KafkaPostgresTestContainerSupport {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoBean
    private PaymentAuditInboxTransactionService transactionService;

    @Test
    void databaseFailureDoesNotCommitOffsetAndRecordIsRedelivered() throws Exception {
        UUID eventId = UUID.randomUUID();
        String payload = payload(eventId);
        when(transactionService.consume(eq("1"), eq(payload), any()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"))
                .thenReturn(PaymentAuditConsumeResult.processed("PaymentApproved"));

        kafkaTemplate.send("ticketledger-payment-events", "1", payload).get(10, TimeUnit.SECONDS);

        verify(transactionService, timeout(15000).times(2))
                .consume(eq("1"), eq(payload), any());
    }

    private String payload(UUID eventId) {
        return """
                {"eventId":"%s","eventType":"PaymentApproved","eventVersion":1,"paymentId":1,"orderId":"order-1","reservationGroupId":2,"userId":3,"totalAmount":11000,"currency":"KRW","occurredAt":"2026-08-25T00:00:00Z","source":"NORMAL"}
                """.formatted(eventId);
    }
}
