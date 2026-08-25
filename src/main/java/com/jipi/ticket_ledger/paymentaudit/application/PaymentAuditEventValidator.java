package com.jipi.ticket_ledger.paymentaudit.application;

import com.jipi.ticket_ledger.paymentaudit.application.model.PaymentAuditEvent;

import java.util.Set;

public final class PaymentAuditEventValidator {

    private static final Set<String> EVENT_TYPES = Set.of("PaymentApproved", "PaymentCanceled");
    private static final Set<String> SOURCES = Set.of("NORMAL", "RECOVERY");

    private PaymentAuditEventValidator() {
    }

    public static void validate(
            String expectedTopic,
            String messageKey,
            String receivedTopic,
            PaymentAuditEvent event
    ) {
        if (!expectedTopic.equals(receivedTopic)) {
            reject("허용되지 않은 topic에서 결제 이벤트를 수신했습니다.");
        }
        if (event.eventId() == null) {
            reject("eventId가 없습니다.");
        }
        if (!EVENT_TYPES.contains(event.eventType())) {
            reject("지원하지 않는 eventType입니다.");
        }
        if (event.eventVersion() == null || event.eventVersion() != 1) {
            reject("지원하지 않는 eventVersion입니다.");
        }
        if (event.paymentId() == null || event.paymentId() <= 0) {
            reject("paymentId가 올바르지 않습니다.");
        }
        if (!event.paymentId().toString().equals(messageKey)) {
            reject("Kafka key와 paymentId가 일치하지 않습니다.");
        }
        if (event.orderId() == null || event.orderId().isBlank()) {
            reject("orderId가 없습니다.");
        }
        if (event.reservationGroupId() == null || event.reservationGroupId() <= 0) {
            reject("reservationGroupId가 올바르지 않습니다.");
        }
        if (event.userId() == null || event.userId() <= 0) {
            reject("userId가 올바르지 않습니다.");
        }
        if (event.totalAmount() == null || event.totalAmount() <= 0) {
            reject("totalAmount가 올바르지 않습니다.");
        }
        if (!"KRW".equals(event.currency())) {
            reject("지원하지 않는 currency입니다.");
        }
        if (event.occurredAt() == null) {
            reject("occurredAt이 없습니다.");
        }
        if (!SOURCES.contains(event.source())) {
            reject("지원하지 않는 source입니다.");
        }
    }

    private static void reject(String message) {
        throw new PaymentAuditEventValidationException(message);
    }
}
