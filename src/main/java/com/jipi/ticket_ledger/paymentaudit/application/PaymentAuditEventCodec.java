package com.jipi.ticket_ledger.paymentaudit.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jipi.ticket_ledger.paymentaudit.application.model.PaymentAuditEvent;
import com.jipi.ticket_ledger.global.crypto.Sha256Hasher;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class PaymentAuditEventCodec {

    private final ObjectMapper strictObjectMapper;
    private final PaymentAuditInboxProperties properties;

    public PaymentAuditEventCodec(ObjectMapper objectMapper, PaymentAuditInboxProperties properties) {
        this.strictObjectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.properties = properties;
    }

    public DecodedPaymentAuditEvent decode(String payload) {
        if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > properties.maxPayloadBytes()) {
            throw new PaymentAuditEventValidationException("결제 이벤트 payload 크기가 허용 범위를 벗어났습니다.");
        }

        try {
            JsonNode root = strictObjectMapper.readTree(payload);
            if (root == null || !root.isObject()) {
                throw new PaymentAuditEventValidationException("결제 이벤트 payload는 JSON object여야 합니다.");
            }
            if (root.has("paymentKey")) {
                throw new PaymentAuditEventValidationException("결제 이벤트에 paymentKey를 포함할 수 없습니다.");
            }
            PaymentAuditEvent event = strictObjectMapper.treeToValue(root, PaymentAuditEvent.class);
            String canonicalPayload = strictObjectMapper.writeValueAsString(event);
            return new DecodedPaymentAuditEvent(
                    event,
                    Sha256Hasher.hash(canonicalPayload)
            );
        } catch (PaymentAuditEventValidationException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new PaymentAuditEventValidationException("결제 이벤트 payload를 해석할 수 없습니다.", exception);
        }
    }

    public record DecodedPaymentAuditEvent(PaymentAuditEvent event, String payloadHash) {
    }
}
