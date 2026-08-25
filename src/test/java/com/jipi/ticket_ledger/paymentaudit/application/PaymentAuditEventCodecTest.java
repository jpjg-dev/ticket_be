package com.jipi.ticket_ledger.paymentaudit.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentAuditEventCodecTest {

    private final PaymentAuditEventCodec codec = new PaymentAuditEventCodec(
            new ObjectMapper().registerModule(new JavaTimeModule()),
            properties()
    );

    @Test
    void semanticPayloadUsesSameCanonicalHashRegardlessOfFieldOrder() {
        String first = """
                {"eventId":"11111111-1111-1111-1111-111111111111","eventType":"PaymentApproved","eventVersion":1,"paymentId":1,"orderId":"order-1","reservationGroupId":2,"userId":3,"totalAmount":11000,"currency":"KRW","occurredAt":"2026-08-25T00:00:00Z","source":"NORMAL"}
                """;
        String reordered = """
                {"source":"NORMAL","currency":"KRW","totalAmount":11000,"userId":3,"reservationGroupId":2,"orderId":"order-1","paymentId":1,"eventVersion":1,"eventType":"PaymentApproved","occurredAt":"2026-08-25T00:00:00Z","eventId":"11111111-1111-1111-1111-111111111111"}
                """;

        String firstHash = codec.decode(first).payloadHash();
        assertEquals(firstHash, codec.decode(reordered).payloadHash());
        assertEquals("b84b374b040953ec75155e2e0a90783074cc0114b7c75a9bd5f3903696af2063", firstHash);
    }

    @Test
    void unknownFieldAndPaymentKeyAreRejected() {
        String unknown = validPayload().replace("}", ",\"unknown\":true}");
        String paymentKey = validPayload().replace("}", ",\"paymentKey\":\"secret\"}");

        assertThrows(PaymentAuditEventValidationException.class, () -> codec.decode(unknown));
        assertThrows(PaymentAuditEventValidationException.class, () -> codec.decode(paymentKey));
    }

    private String validPayload() {
        return """
                {"eventId":"11111111-1111-1111-1111-111111111111","eventType":"PaymentApproved","eventVersion":1,"paymentId":1,"orderId":"order-1","reservationGroupId":2,"userId":3,"totalAmount":11000,"currency":"KRW","occurredAt":"2026-08-25T00:00:00Z","source":"NORMAL"}
                """;
    }

    private PaymentAuditInboxProperties properties() {
        return new PaymentAuditInboxProperties(
                true,
                "ticketledger-payment-events",
                "ticketledger-payment-audit-v1",
                1,
                20,
                Duration.ofSeconds(5),
                Duration.ofSeconds(1),
                3,
                65536,
                "ticketledger-payment-audit-dlt",
                3
        );
    }
}
