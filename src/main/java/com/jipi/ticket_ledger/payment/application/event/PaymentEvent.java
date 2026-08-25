package com.jipi.ticket_ledger.payment.application.event;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.jipi.ticket_ledger.payment.domain.Payment;
import com.jipi.ticket_ledger.payment.domain.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

@JsonPropertyOrder({
        "eventId", "eventType", "eventVersion", "paymentId", "orderId",
        "reservationGroupId", "userId", "totalAmount", "currency", "occurredAt", "source"
})
public record PaymentEvent(
        UUID eventId,
        String eventType,
        int eventVersion,
        Long paymentId,
        String orderId,
        Long reservationGroupId,
        Long userId,
        Integer totalAmount,
        String currency,
        Instant occurredAt,
        PaymentEventSource source
) {

    public static PaymentEvent approved(Payment payment, PaymentEventSource source) {
        if (payment.getStatus() != PaymentStatus.APPROVED || payment.getApprovedAt() == null) {
            throw new IllegalStateException("승인 완료된 결제만 승인 이벤트를 생성할 수 있습니다.");
        }
        return from(payment, PaymentEventType.PAYMENT_APPROVED, payment.getApprovedAt(), source);
    }

    public static PaymentEvent canceled(Payment payment, PaymentEventSource source) {
        if (payment.getStatus() != PaymentStatus.CANCELED || payment.getCanceledAt() == null) {
            throw new IllegalStateException("취소 완료된 결제만 취소 이벤트를 생성할 수 있습니다.");
        }
        return from(payment, PaymentEventType.PAYMENT_CANCELED, payment.getCanceledAt(), source);
    }

    private static PaymentEvent from(
            Payment payment,
            PaymentEventType type,
            Instant occurredAt,
            PaymentEventSource source
    ) {
        return new PaymentEvent(
                UUID.randomUUID(),
                type.eventName(),
                type.version(),
                payment.getId(),
                payment.getOrderId(),
                payment.getReservationGroup().getId(),
                payment.getReservationGroup().getUser().getId(),
                payment.totalAmountWithVat(),
                payment.getCurrency(),
                occurredAt,
                source
        );
    }
}
