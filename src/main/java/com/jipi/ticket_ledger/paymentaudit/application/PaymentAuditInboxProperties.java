package com.jipi.ticket_ledger.paymentaudit.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "payment.audit.consumer")
public record PaymentAuditInboxProperties(
        boolean enabled,
        String topic,
        String groupId,
        int concurrency,
        int maxPollRecords,
        Duration databaseRetryDelay,
        Duration unknownRetryDelay,
        int unknownMaxAttempts,
        int maxPayloadBytes,
        String dltTopic,
        int topicPartitions
) {

    public PaymentAuditInboxProperties {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("payment.audit.consumer.topic은 비어 있을 수 없습니다.");
        }
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("payment.audit.consumer.group-id는 비어 있을 수 없습니다.");
        }
        if (dltTopic == null || dltTopic.isBlank()) {
            throw new IllegalArgumentException("payment.audit.consumer.dlt-topic은 비어 있을 수 없습니다.");
        }
        if (concurrency <= 0 || maxPollRecords <= 0 || unknownMaxAttempts <= 0
                || maxPayloadBytes <= 0 || topicPartitions <= 0) {
            throw new IllegalArgumentException("Payment audit consumer의 수량 설정은 1 이상이어야 합니다.");
        }
        requirePositive(databaseRetryDelay, "database-retry-delay");
        requirePositive(unknownRetryDelay, "unknown-retry-delay");
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("payment.audit.consumer." + property + "는 양수여야 합니다.");
        }
    }
}
