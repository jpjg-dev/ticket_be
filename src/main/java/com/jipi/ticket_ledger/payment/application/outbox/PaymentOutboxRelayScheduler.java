package com.jipi.ticket_ledger.payment.application.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "payment.outbox.relay.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class PaymentOutboxRelayScheduler {

    private final PaymentOutboxRelayService paymentOutboxRelayService;

    @Scheduled(
            fixedDelayString = "${payment.outbox.relay.fixed-delay}",
            scheduler = "paymentOutboxTaskScheduler"
    )
    public void publishPendingEvents() {
        try {
            PaymentOutboxRelayResult result = paymentOutboxRelayService.publishBatch();
            if (result.claimedCount() > 0) {
                log.info("Payment Outbox relay completed. claimed={} published={} retryScheduled={} holdManual={} staleResult={}",
                        result.claimedCount(), result.publishedCount(), result.retryScheduledCount(),
                        result.holdManualCount(), result.staleResultCount());
            } else {
                log.debug("Payment Outbox relay completed with no claimable events.");
            }
        } catch (Exception exception) {
            log.error("Payment Outbox relay cycle failed, retrying on next schedule.", exception);
        }
    }
}
