package com.jipi.ticket_ledger.payment.infrastructure.kafka;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jipi.ticket_ledger.payment.application.outbox.PaymentOutboxRelayResult;
import com.jipi.ticket_ledger.payment.application.outbox.PaymentOutboxRelayService;
import com.jipi.ticket_ledger.payment.application.outbox.PaymentOutboxRelayProperties;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentRepository;
import com.jipi.ticket_ledger.payment.infrastructure.outbox.PaymentOutboxEvent;
import com.jipi.ticket_ledger.payment.infrastructure.outbox.PaymentOutboxEventRepository;
import com.jipi.ticket_ledger.payment.infrastructure.outbox.PaymentOutboxStatus;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroup;
import com.jipi.ticket_ledger.reservation.domain.ReservationGroupRepository;
import com.jipi.ticket_ledger.support.KafkaPostgresTestContainerSupport;
import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=OFF",
        "app.scheduling.enabled=false",
        "payment.outbox.relay.enabled=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentOutboxKafkaIntegrationTest extends KafkaPostgresTestContainerSupport {

    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    @Autowired
    private PaymentOutboxRelayService relayService;

    @Autowired
    private PaymentOutboxRelayProperties properties;

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
        outboxRepository.deleteAllInBatch();
        paymentIds.forEach(paymentRepository::deleteById);
        groupIds.forEach(reservationGroupRepository::deleteById);
        userIds.forEach(userRepository::deleteById);
    }

    @Test
    void relayPublishesSamePaymentEventsInOrderAndMarksThemPublished() {
        Payment payment = payment();
        UUID approvedEventId = UUID.randomUUID();
        UUID canceledEventId = UUID.randomUUID();
        outbox(payment, approvedEventId, "PaymentApproved");
        outbox(payment, canceledEventId, "PaymentCanceled");

        PaymentOutboxRelayResult result = relayService.publishBatch();

        assertEquals(2, result.publishedCount());
        List<PaymentOutboxEvent> stored = outboxRepository.findAllByPaymentIdOrderByIdAsc(payment.getId());
        assertTrue(stored.stream().allMatch(event -> event.getStatus() == PaymentOutboxStatus.PUBLISHED));

        List<ConsumerRecord<String, String>> records = consumePaymentRecords(payment.getId(), 2);
        assertEquals(2, records.size());
        assertEquals(payment.getId().toString(), records.get(0).key());
        assertEquals(records.get(0).partition(), records.get(1).partition());
        assertTrue(records.get(0).value().contains(approvedEventId.toString()));
        assertTrue(records.get(1).value().contains(canceledEventId.toString()));
    }

    private List<ConsumerRecord<String, String>> consumePaymentRecords(Long paymentId, int expectedCount) {
        Map<String, Object> consumerProperties = new HashMap<>();
        consumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, "payment-outbox-test-" + UUID.randomUUID());
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        List<ConsumerRecord<String, String>> matched = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties)) {
            consumer.subscribe(List.of(properties.topic()));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (matched.size() < expectedCount && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> {
                    if (paymentId.toString().equals(record.key())) {
                        matched.add(record);
                    }
                });
            }
        }
        return matched;
    }

    private Payment payment() {
        String suffix = String.valueOf(System.nanoTime());
        User user = userRepository.save(new User(
                "kafka-outbox-" + suffix + "@test.com",
                "password",
                "Kafka Outbox",
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
                "kafka-outbox-" + suffix,
                "KRW"
        ));
        paymentIds.add(payment.getId());
        return payment;
    }

    private void outbox(Payment payment, UUID eventId, String eventType) {
        outboxRepository.saveAndFlush(PaymentOutboxEvent.pending(
                eventId,
                payment.getId(),
                eventType,
                1,
                JsonNodeFactory.instance.objectNode()
                        .put("eventId", eventId.toString())
                        .put("eventType", eventType)
                        .put("paymentId", payment.getId()),
                NOW,
                NOW
        ));
    }
}
