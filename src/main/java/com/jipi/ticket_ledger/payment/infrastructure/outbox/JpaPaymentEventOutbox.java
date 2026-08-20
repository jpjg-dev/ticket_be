package com.jipi.ticket_ledger.payment.infrastructure.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jipi.ticket_ledger.payment.application.event.PaymentEvent;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentEventOutbox;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Component
@RequiredArgsConstructor
public class JpaPaymentEventOutbox implements PaymentEventOutbox {

    private final PaymentOutboxEventRepository paymentOutboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(PaymentEvent event) {
        paymentOutboxEventRepository.save(PaymentOutboxEvent.pending(
                event.eventId(),
                event.paymentId(),
                event.eventType(),
                event.eventVersion(),
                serialize(event),
                event.occurredAt(),
                clock.instant()
        ));
    }

    private JsonNode serialize(PaymentEvent event) {
        try {
            return objectMapper.valueToTree(event);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("결제 이벤트를 Outbox payload로 변환할 수 없습니다.", exception);
        }
    }
}
