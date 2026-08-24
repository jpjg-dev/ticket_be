package com.jipi.ticket_ledger.payment.infrastructure.kafka;

import com.jipi.ticket_ledger.payment.application.outbox.PaymentOutboxRelayProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(name = "payment.outbox.relay.enabled", havingValue = "true")
public class PaymentKafkaTopicConfiguration {

    @Bean
    public NewTopic paymentEventsTopic(PaymentOutboxRelayProperties properties) {
        return TopicBuilder.name(properties.topic())
                .partitions(properties.partitions())
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(properties.retention().toMillis()))
                .build();
    }
}
