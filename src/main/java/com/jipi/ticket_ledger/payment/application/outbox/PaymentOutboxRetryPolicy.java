package com.jipi.ticket_ledger.payment.application.outbox;

import com.jipi.ticket_ledger.payment.application.port.out.PaymentEventPublishFailureKind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class PaymentOutboxRetryPolicy {

    private final PaymentOutboxRelayProperties properties;

    public boolean shouldHold(PaymentEventPublishFailureKind failureKind, int nextRetryCount) {
        if (failureKind == PaymentEventPublishFailureKind.POISON) {
            return true;
        }
        return failureKind == PaymentEventPublishFailureKind.UNKNOWN
                && nextRetryCount >= properties.unknownMaxAttempts();
    }

    public Duration backoff(int nextRetryCount) {
        Duration delay = properties.retryInitialDelay();
        for (int attempt = 1; attempt < nextRetryCount; attempt++) {
            if (delay.compareTo(properties.retryMaxDelay()) >= 0) {
                return properties.retryMaxDelay();
            }
            Duration doubled;
            try {
                doubled = delay.multipliedBy(2);
            } catch (ArithmeticException exception) {
                return properties.retryMaxDelay();
            }
            delay = doubled.compareTo(properties.retryMaxDelay()) > 0
                    ? properties.retryMaxDelay()
                    : doubled;
        }
        return delay;
    }
}
