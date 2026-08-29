package com.jipi.ticket_ledger.paymentaudit.infrastructure.kafka;

import com.jipi.ticket_ledger.paymentaudit.application.PaymentAuditInboxProperties;
import com.jipi.ticket_ledger.paymentaudit.application.observability.PaymentAuditMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class PaymentAuditKafkaConfigurationTest {

    private final PaymentAuditKafkaConfiguration configuration = new PaymentAuditKafkaConfiguration();

    @Test
    void zeroIdleBetweenPollsPreservesDefaultPollingBehavior() {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = createFactory(Duration.ZERO);

        assertEquals(0, factory.getContainerProperties().getIdleBetweenPolls());
    }

    @Test
    void positiveIdleBetweenPollsIsWiredToContainerPropertiesInMilliseconds() {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = createFactory(Duration.ofMillis(250));

        assertEquals(250, factory.getContainerProperties().getIdleBetweenPolls());
    }

    private ConcurrentKafkaListenerContainerFactory<Object, Object> createFactory(Duration idleBetweenPolls) {
        return configuration.paymentAuditKafkaListenerContainerFactory(
                mock(ConcurrentKafkaListenerContainerFactoryConfigurer.class),
                mock(ConsumerFactory.class),
                mock(KafkaTemplate.class),
                properties(idleBetweenPolls),
                mock(PaymentAuditMetrics.class)
        );
    }

    private PaymentAuditInboxProperties properties(Duration idleBetweenPolls) {
        return new PaymentAuditInboxProperties(
                true,
                "ticketledger-payment-events",
                "ticketledger-payment-audit-v1",
                1,
                20,
                idleBetweenPolls,
                Duration.ofSeconds(5),
                Duration.ofSeconds(1),
                3,
                65536,
                "ticketledger-payment-audit-dlt",
                3
        );
    }
}
