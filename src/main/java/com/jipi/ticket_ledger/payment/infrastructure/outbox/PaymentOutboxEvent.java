package com.jipi.ticket_ledger.payment.infrastructure.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(name = "payment_outbox_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentOutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private UUID eventId;

    @Column(nullable = false)
    private Long paymentId;

    @Column(nullable = false, length = 50)
    private String eventType;

    @Column(nullable = false)
    private Integer eventVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(length = 64)
    private String payloadHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentOutboxStatus status;

    @Column(nullable = false)
    private Integer retryCount;

    private Instant nextRetryAt;

    @Column(length = 1000)
    private String lastError;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant publishedAt;

    private UUID claimToken;

    private Instant claimedUntil;

    private PaymentOutboxEvent(
            UUID eventId,
            Long paymentId,
            String eventType,
            Integer eventVersion,
            JsonNode payload,
            String payloadHash,
            Instant occurredAt,
            Instant createdAt
    ) {
        this.eventId = eventId;
        this.paymentId = paymentId;
        this.eventType = eventType;
        this.eventVersion = eventVersion;
        this.payload = payload;
        this.payloadHash = payloadHash;
        this.status = PaymentOutboxStatus.PENDING;
        this.retryCount = 0;
        this.occurredAt = occurredAt;
        this.createdAt = createdAt;
    }

    public static PaymentOutboxEvent pending(
            UUID eventId,
            Long paymentId,
            String eventType,
            Integer eventVersion,
            JsonNode payload,
            String payloadHash,
            Instant occurredAt,
            Instant createdAt
    ) {
        return new PaymentOutboxEvent(
                eventId,
                paymentId,
                eventType,
                eventVersion,
                payload,
                payloadHash,
                occurredAt,
                createdAt
        );
    }

    public static PaymentOutboxEvent pending(
            UUID eventId,
            Long paymentId,
            String eventType,
            Integer eventVersion,
            JsonNode payload,
            Instant occurredAt,
            Instant createdAt
    ) {
        return pending(eventId, paymentId, eventType, eventVersion, payload, null, occurredAt, createdAt);
    }
}
