package com.jipi.ticket_ledger.payment.infrastructure.kafka;

import com.jipi.ticket_ledger.payment.application.outbox.ClaimedPaymentOutboxEvent;
import com.jipi.ticket_ledger.payment.application.outbox.PaymentOutboxRelayProperties;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentEventPublishException;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentEventPublishFailureKind;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentEventPublisher;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@RequiredArgsConstructor
public class KafkaPaymentEventPublisher implements PaymentEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final PaymentOutboxRelayProperties properties;

    @Override
    public void publish(ClaimedPaymentOutboxEvent event) {
        try {
            kafkaTemplate.send(
                            properties.topic(),
                            event.paymentId().toString(),
                            event.payload()
                    )
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(PaymentEventPublishFailureKind.TRANSIENT, exception);
        } catch (TimeoutException exception) {
            throw failure(PaymentEventPublishFailureKind.TRANSIENT, exception);
        } catch (ExecutionException exception) {
            throw classify(exception.getCause());
        } catch (RuntimeException exception) {
            throw classify(exception);
        }
    }

    private PaymentEventPublishException classify(Throwable throwable) {
        Throwable cause = rootCause(throwable);
        if (cause instanceof RecordTooLargeException
                || cause instanceof SerializationException) {
            return failure(PaymentEventPublishFailureKind.POISON, cause);
        }
        if (cause instanceof KafkaException) {
            return failure(PaymentEventPublishFailureKind.TRANSIENT, cause);
        }
        return failure(PaymentEventPublishFailureKind.UNKNOWN, cause);
    }

    private PaymentEventPublishException failure(
            PaymentEventPublishFailureKind kind,
            Throwable cause
    ) {
        String errorCode = kind.name() + "_" + cause.getClass().getSimpleName();
        return new PaymentEventPublishException(kind, errorCode, cause);
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
