package com.jipi.ticket_ledger.paymentaudit.application.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;
import java.util.UUID;

@JsonPropertyOrder({
        "eventId", "eventType", "eventVersion", "paymentId", "orderId",
        "reservationGroupId", "userId", "totalAmount", "currency", "occurredAt", "source"
})
public record PaymentAuditEvent(
        UUID eventId,
        String eventType,
        Integer eventVersion,
        Long paymentId,
        String orderId,
        Long reservationGroupId,
        Long userId,
        Integer totalAmount,
        String currency,
        Instant occurredAt,
        String source
) {
}
