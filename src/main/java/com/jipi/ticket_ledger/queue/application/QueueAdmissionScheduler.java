package com.jipi.ticket_ledger.queue.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class QueueAdmissionScheduler {

    private final QueueAdmissionStore queueAdmissionStore;
    private final QueueAdmissionMetrics metrics;

    @Scheduled(fixedDelayString = "${queue.admission.fixed-delay-ms}")
    public void admitNext() {
        try {
            int admitted = queueAdmissionStore.admitNextForActiveSchedules();
            metrics.recordAdmitted(admitted);
            metrics.setWaitingUsers(queueAdmissionStore.countTotalWaiting());
        } catch (DataAccessException exception) {
            metrics.record("ENFORCED", "store_unavailable");
            log.warn("Queue admission scheduler skipped because Redis is unavailable");
        }
    }
}
