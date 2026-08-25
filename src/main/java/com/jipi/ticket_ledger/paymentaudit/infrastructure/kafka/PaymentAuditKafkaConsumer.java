package com.jipi.ticket_ledger.paymentaudit.infrastructure.kafka;

import com.jipi.ticket_ledger.paymentaudit.application.PaymentAuditInboxTransactionService;
import com.jipi.ticket_ledger.paymentaudit.application.model.PaymentAuditConsumeResult;
import com.jipi.ticket_ledger.paymentaudit.application.model.PaymentAuditRecordMetadata;
import com.jipi.ticket_ledger.paymentaudit.application.observability.PaymentAuditMetrics;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
@ConditionalOnProperty(name = "payment.audit.consumer.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PaymentAuditKafkaConsumer {

    private final PaymentAuditInboxTransactionService transactionService;
    private final PaymentAuditMetrics paymentAuditMetrics;
    private final Clock clock;

    @KafkaListener(
            topics = "${payment.audit.consumer.topic}",
            groupId = "${payment.audit.consumer.group-id}",
            containerFactory = "paymentAuditKafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record) {
        PaymentAuditConsumeResult result = transactionService.consume(
                record.key(),
                record.value(),
                new PaymentAuditRecordMetadata(
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        clock.instant()
                )
        );
        paymentAuditMetrics.recordConsume(
                result.outcome().name().toLowerCase(java.util.Locale.ROOT)
        );
        if (result.outcome() == PaymentAuditConsumeResult.Outcome.PROCESSED) {
            paymentAuditMetrics.recordAudit(result.eventType());
        }
    }
}
