package com.jipi.ticket_ledger.payment.application.outbox;

import com.jipi.ticket_ledger.payment.application.observability.PaymentOutboxMetrics;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentEventPublishException;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentEventPublishFailureKind;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentEventPublisher;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentOutboxRelayStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentOutboxRelayService {

    private final PaymentOutboxRelayStore paymentOutboxRelayStore;
    private final PaymentEventPublisher paymentEventPublisher;
    private final PaymentOutboxRetryPolicy retryPolicy;
    private final PaymentOutboxRelayProperties properties;
    private final PaymentOutboxMetrics paymentOutboxMetrics;
    private final Clock clock;

    public PaymentOutboxRelayResult publishBatch() {
        int claimedCount = 0;
        int publishedCount = 0;
        int retryScheduledCount = 0;
        int holdManualCount = 0;
        int staleResultCount = 0;

        for (int index = 0; index < properties.batchSize(); index++) {
            Instant claimStartedAt = clock.instant();
            Optional<ClaimedPaymentOutboxEvent> claimed = paymentOutboxRelayStore.claimNext(
                    claimStartedAt,
                    claimStartedAt.plus(properties.leaseDuration()),
                    UUID.randomUUID()
            );
            if (claimed.isEmpty()) {
                break;
            }

            claimedCount++;
            ClaimedPaymentOutboxEvent event = claimed.get();
            long publishStartedAtNanos = System.nanoTime();
            try {
                paymentEventPublisher.publish(event);
            } catch (PaymentEventPublishException exception) {
                FailureResult failureResult = handleFailure(event, exception);
                retryScheduledCount += failureResult.retryScheduledCount();
                holdManualCount += failureResult.holdManualCount();
                staleResultCount += failureResult.staleResultCount();
                paymentOutboxMetrics.recordFailure(exception.getFailureKind().name().toLowerCase(Locale.ROOT));
                paymentOutboxMetrics.recordPublish(
                        failureResult.metricOutcome(),
                        elapsedSince(publishStartedAtNanos)
                );
                continue;
            } catch (RuntimeException exception) {
                PaymentEventPublishException wrapped = new PaymentEventPublishException(
                        PaymentEventPublishFailureKind.UNKNOWN,
                        "UNKNOWN_" + exception.getClass().getSimpleName(),
                        exception
                );
                FailureResult failureResult = handleFailure(event, wrapped);
                retryScheduledCount += failureResult.retryScheduledCount();
                holdManualCount += failureResult.holdManualCount();
                staleResultCount += failureResult.staleResultCount();
                paymentOutboxMetrics.recordFailure(wrapped.getFailureKind().name().toLowerCase(Locale.ROOT));
                paymentOutboxMetrics.recordPublish(
                        failureResult.metricOutcome(),
                        elapsedSince(publishStartedAtNanos)
                );
                continue;
            }

            // Kafka ACK 이후 DB 반영 실패는 publish 실패가 아니다. 예외를 상위로 전파해 claim lease를
            // 유지하고, lease 만료 뒤 같은 eventId가 at-least-once 방식으로 재발행되게 한다.
            boolean updated = paymentOutboxRelayStore.markPublished(
                    event.outboxId(),
                    event.claimToken(),
                    clock.instant()
            );
            if (updated) {
                publishedCount++;
                paymentOutboxMetrics.recordPublish(
                        "published",
                        elapsedSince(publishStartedAtNanos)
                );
            } else {
                staleResultCount++;
                paymentOutboxMetrics.recordPublish(
                        "stale_result",
                        elapsedSince(publishStartedAtNanos)
                );
            }
        }

        refreshBacklogMetrics();
        return new PaymentOutboxRelayResult(
                claimedCount,
                publishedCount,
                retryScheduledCount,
                holdManualCount,
                staleResultCount
        );
    }

    public void refreshBacklogMetrics() {
        paymentOutboxMetrics.updateBacklog(paymentOutboxRelayStore.getBacklogSnapshot(clock.instant()));
    }

    private FailureResult handleFailure(
            ClaimedPaymentOutboxEvent event,
            PaymentEventPublishException exception
    ) {
        int nextRetryCount = event.retryCount() + 1;
        String errorCode = safeErrorCode(exception);
        if (retryPolicy.shouldHold(exception.getFailureKind(), nextRetryCount)) {
            boolean updated = paymentOutboxRelayStore.holdManual(
                    event.outboxId(),
                    event.claimToken(),
                    errorCode
            );
            if (!updated) {
                return FailureResult.stale();
            }
            log.error("Payment Outbox event moved to HOLD_MANUAL. outboxId={} eventId={} paymentId={} errorCode={}",
                    event.outboxId(), event.eventId(), event.paymentId(), errorCode);
            return FailureResult.held();
        }

        Instant nextRetryAt = clock.instant().plus(retryPolicy.backoff(nextRetryCount));
        boolean updated = paymentOutboxRelayStore.scheduleRetry(
                event.outboxId(),
                event.claimToken(),
                nextRetryAt,
                errorCode
        );
        if (!updated) {
            return FailureResult.stale();
        }
        log.warn("Payment Outbox publish failed, retry scheduled. outboxId={} eventId={} paymentId={} retryCount={} nextRetryAt={} errorCode={}",
                event.outboxId(), event.eventId(), event.paymentId(), nextRetryCount, nextRetryAt, errorCode);
        return FailureResult.retryScheduled();
    }

    private String safeErrorCode(PaymentEventPublishException exception) {
        String code = exception.getErrorCode();
        if (code == null || code.isBlank()) {
            code = exception.getFailureKind().name();
        }
        return code.length() <= 1000 ? code : code.substring(0, 1000);
    }

    private Duration elapsedSince(long startedAtNanos) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedAtNanos));
    }

    private record FailureResult(
            int retryScheduledCount,
            int holdManualCount,
            int staleResultCount,
            String metricOutcome
    ) {
        private static FailureResult retryScheduled() {
            return new FailureResult(1, 0, 0, "retry_scheduled");
        }

        private static FailureResult held() {
            return new FailureResult(0, 1, 0, "hold_manual");
        }

        private static FailureResult stale() {
            return new FailureResult(0, 0, 1, "stale_result");
        }
    }
}
