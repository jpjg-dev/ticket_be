package com.jipi.ticket_ledger.paymentaudit.infrastructure.kafka;

import com.jipi.ticket_ledger.support.KafkaPostgresTestContainerSupport;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=OFF",
        "app.scheduling.enabled=false",
        "payment.outbox.relay.enabled=true",
        "payment.audit.consumer.enabled=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentAuditKafkaConsumerIntegrationTest extends KafkaPostgresTestContainerSupport {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM payment_event_audit");
        jdbcTemplate.update("DELETE FROM payment_event_inbox");
    }

    @Test
    void poisonMovesToDltAndFollowingRecordsAreProcessedIdempotently() throws Exception {
        String topic = "ticketledger-payment-events";
        String dltTopic = "ticketledger-payment-audit-dlt";
        UUID approvedEventId = UUID.randomUUID();
        UUID canceledEventId = UUID.randomUUID();
        String approved = payload(approvedEventId, "PaymentApproved");
        String canceled = payload(canceledEventId, "PaymentCanceled");

        kafkaTemplate.send(topic, "1", "{").get(10, TimeUnit.SECONDS);
        kafkaTemplate.send(topic, "1", approved).get(10, TimeUnit.SECONDS);
        kafkaTemplate.send(topic, "1", approved).get(10, TimeUnit.SECONDS);
        kafkaTemplate.send(topic, "1", canceled).get(10, TimeUnit.SECONDS);

        awaitAuditCount(2);

        assertEquals(2, count("payment_event_audit"));
        assertEquals(2, count("payment_event_inbox"));
        assertTrue(consumeValues(dltTopic).stream().anyMatch("{"::equals));
    }

    private void awaitAuditCount(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (count("payment_event_audit") == expected) {
                return;
            }
            Thread.sleep(100);
        }
        assertEquals(expected, count("payment_event_audit"));
    }

    private List<String> consumeValues(String topic) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "payment-audit-dlt-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (values.isEmpty() && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> values.add(record.value()));
            }
        }
        return values;
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private String payload(UUID eventId, String eventType) {
        return """
                {"eventId":"%s","eventType":"%s","eventVersion":1,"paymentId":1,"orderId":"order-1","reservationGroupId":2,"userId":3,"totalAmount":11000,"currency":"KRW","occurredAt":"2026-08-25T00:00:00Z","source":"NORMAL"}
                """.formatted(eventId, eventType);
    }
}
