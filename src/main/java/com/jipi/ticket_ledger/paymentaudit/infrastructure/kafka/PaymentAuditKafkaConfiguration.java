package com.jipi.ticket_ledger.paymentaudit.infrastructure.kafka;

import com.jipi.ticket_ledger.paymentaudit.application.PaymentAuditEventValidationException;
import com.jipi.ticket_ledger.paymentaudit.application.PaymentAuditInboxProperties;
import com.jipi.ticket_ledger.paymentaudit.application.PaymentInboxPayloadConflictException;
import com.jipi.ticket_ledger.paymentaudit.application.observability.PaymentAuditMetrics;
import com.jipi.ticket_ledger.paymentaudit.application.maintenance.PaymentEventMaintenanceProperties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.transaction.TransactionException;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableKafka
@EnableConfigurationProperties(PaymentAuditInboxProperties.class)
public class PaymentAuditKafkaConfiguration {

    @Bean
    @ConditionalOnProperty(name = "payment.audit.consumer.enabled", havingValue = "true")
    public NewTopic paymentAuditDltTopic(
            PaymentAuditInboxProperties auditProperties,
            PaymentEventMaintenanceProperties maintenanceProperties
    ) {
        return TopicBuilder.name(auditProperties.dltTopic())
                .partitions(auditProperties.topicPartitions())
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(maintenanceProperties.dltRetention().toMillis()))
                .build();
    }

    @Bean(name = "paymentAuditKafkaListenerContainerFactory")
    @ConditionalOnProperty(name = "payment.audit.consumer.enabled", havingValue = "true")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> paymentAuditKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<Object, Object> kafkaTemplate,
            PaymentAuditInboxProperties properties,
            PaymentAuditMetrics metrics
    ) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setConcurrency(properties.concurrency());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);

        DeadLetterPublishingRecoverer deadLetterRecoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(properties.dltTopic(), record.partition())
        );
        deadLetterRecoverer.setFailIfSendResultIsError(true);
        deadLetterRecoverer.setWaitForSendResultTimeout(java.time.Duration.ofSeconds(10));

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                (record, exception) -> {
                    deadLetterRecoverer.accept(record, exception);
                    metrics.recordConsume("dlt");
                },
                new FixedBackOff(
                        properties.unknownRetryDelay().toMillis(),
                        properties.unknownMaxAttempts() - 1L
                )
        );
        errorHandler.addNotRetryableExceptions(
                PaymentAuditEventValidationException.class,
                PaymentInboxPayloadConflictException.class
        );
        errorHandler.setBackOffFunction((record, exception) -> containsDatabaseException(exception)
                ? new FixedBackOff(properties.databaseRetryDelay().toMillis(), Long.MAX_VALUE)
                : null);
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().getKafkaConsumerProperties()
                .setProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, String.valueOf(properties.maxPollRecords()));
        return factory;
    }

    private boolean containsDatabaseException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != current) {
            if (current instanceof DataAccessException
                    || current instanceof TransactionException
                    || current instanceof java.sql.SQLException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
