package com.jipi.ticket_ledger.paymentaudit.application.maintenance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = {"app.scheduling.enabled", "payment.event-maintenance.enabled"},
        havingValue = "true"
)
@RequiredArgsConstructor
@Slf4j
public class PaymentEventMaintenanceScheduler {

    private final PaymentEventMaintenanceService maintenanceService;

    @Scheduled(
            fixedDelayString = "${payment.event-maintenance.fixed-delay}",
            scheduler = "paymentEventMaintenanceTaskScheduler"
    )
    public void cleanup() {
        PaymentEventMaintenanceResult result = maintenanceService.cleanup();
        if (result.deletedOutboxCount() + result.deletedInboxCount() > 0) {
            log.info("Payment event retention cleanup completed. outboxDeleted={} inboxDeleted={}",
                    result.deletedOutboxCount(), result.deletedInboxCount());
        } else {
            log.debug("Payment event retention cleanup completed with no deleted records.");
        }
    }
}
