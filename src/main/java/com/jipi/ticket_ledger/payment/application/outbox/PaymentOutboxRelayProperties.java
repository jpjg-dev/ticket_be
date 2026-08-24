package com.jipi.ticket_ledger.payment.application.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "payment.outbox.relay")
public record PaymentOutboxRelayProperties(
        boolean enabled,
        Duration fixedDelay,
        int batchSize,
        Duration leaseDuration,
        Duration sendTimeout,
        Duration retryInitialDelay,
        Duration retryMaxDelay,
        int unknownMaxAttempts,
        String topic,
        int partitions,
        Duration retention
) {

    public PaymentOutboxRelayProperties {
        requirePositive(fixedDelay, "fixed-delay");
        requirePositive(leaseDuration, "lease-duration");
        requirePositive(sendTimeout, "send-timeout");
        requirePositive(retryInitialDelay, "retry-initial-delay");
        requirePositive(retryMaxDelay, "retry-max-delay");
        requirePositive(retention, "retention");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("payment.outbox.relay.batch-size는 1 이상이어야 합니다.");
        }
        if (unknownMaxAttempts <= 0) {
            throw new IllegalArgumentException("payment.outbox.relay.unknown-max-attempts는 1 이상이어야 합니다.");
        }
        if (partitions <= 0) {
            throw new IllegalArgumentException("payment.outbox.relay.partitions는 1 이상이어야 합니다.");
        }
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("payment.outbox.relay.topic은 비어 있을 수 없습니다.");
        }
        if (leaseDuration.compareTo(sendTimeout) <= 0) {
            throw new IllegalArgumentException("Outbox claim lease는 Kafka send timeout보다 길어야 합니다.");
        }
        if (retryMaxDelay.compareTo(retryInitialDelay) < 0) {
            throw new IllegalArgumentException("Outbox retry 최대 지연은 초기 지연보다 짧을 수 없습니다.");
        }
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("payment.outbox.relay." + property + "는 양수여야 합니다.");
        }
    }
}
