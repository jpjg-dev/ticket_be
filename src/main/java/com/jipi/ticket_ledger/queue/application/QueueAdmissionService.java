package com.jipi.ticket_ledger.queue.application;

import com.jipi.ticket_ledger.featureflag.application.FeatureFlagService;
import com.jipi.ticket_ledger.featureflag.domain.QueueMode;
import com.jipi.ticket_ledger.queue.domain.QueueAdmissionSnapshot;
import com.jipi.ticket_ledger.queue.domain.QueueAdmissionClaimResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;

import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueAdmissionService {

    private final FeatureFlagService featureFlagService;
    private final QueueAdmissionStore queueAdmissionStore;
    private final QueueAdmissionMetrics metrics;
    private final QueueAutoActivationManager autoActivationManager;
    private final QueueLoadMonitor queueLoadMonitor;

    public QueueAdmissionSnapshot enter(Long userId, Long scheduleId) {
        queueLoadMonitor.arrivalObserved();
        return withAvailableQueue(() -> {
            QueueMode mode = effectiveQueueMode();
            if (mode == QueueMode.OFF) {
                metrics.record(mode.name(), "bypassed");
                return QueueAdmissionSnapshot.bypassed();
            }
            if (mode == QueueMode.SHADOW) {
                long waiting = queueAdmissionStore.countWaiting(scheduleId);
                metrics.record(mode.name(), waiting > 0 ? "would_wait" : "would_admit");
                log.info("queue shadow decision userId={} scheduleId={} waiting={} outcome={}",
                        userId, scheduleId, waiting, waiting > 0 ? "WOULD_WAIT" : "WOULD_ADMIT");
                if (autoActivationManager.isAutomaticControlEnabled()) {
                    return QueueAdmissionSnapshot.bypassed(queueAdmissionStore.issueBypassPermit(userId, scheduleId));
                }
                return QueueAdmissionSnapshot.bypassed();
            }

            String queueToken = queueAdmissionStore.register(userId, scheduleId);
            QueueAdmissionSnapshot snapshot = queueAdmissionStore.getStatus(userId, scheduleId, queueToken);
            metrics.record(mode.name(), snapshot.status().name().toLowerCase());
            return snapshot;
        });
    }

    public QueueAdmissionSnapshot getStatus(Long userId, Long scheduleId, String queueToken) {
        if (effectiveQueueMode() != QueueMode.ENFORCED) {
            return QueueAdmissionSnapshot.bypassed();
        }
        return withAvailableQueue(() -> queueAdmissionStore.getStatus(userId, scheduleId, queueToken));
    }

    public QueueAdmissionPermit claimForReservation(Long userId, Long scheduleId, String queueToken) {
        QueueMode mode = effectiveQueueMode();
        if (mode != QueueMode.ENFORCED) {
            return QueueAdmissionPermit.bypassed(userId, scheduleId);
        }
        if (queueToken == null || queueToken.isBlank()) {
            metrics.recordToken("missing");
            throw new QueueAdmissionRequiredException();
        }
        QueueAdmissionClaimResult claimResult = withAvailableQueue(
                () -> queueAdmissionStore.claim(userId, scheduleId, queueToken));
        metrics.recordToken(claimResult.name().toLowerCase());
        if (claimResult != QueueAdmissionClaimResult.CLAIMED) {
            throw new QueueAdmissionRequiredException();
        }
        return QueueAdmissionPermit.claimed(userId, scheduleId, queueToken);
    }

    public void release(QueueAdmissionPermit permit) {
        if (permit.enforced()) {
            withAvailableQueue(() -> queueAdmissionStore.release(
                    permit.userId(), permit.scheduleId(), permit.queueToken()));
            metrics.recordToken("released");
        }
    }

    public void complete(QueueAdmissionPermit permit) {
        if (permit.enforced()) {
            withAvailableQueue(() -> queueAdmissionStore.complete(
                    permit.userId(), permit.scheduleId(), permit.queueToken()));
            metrics.recordToken("completed");
        }
    }

    public void cancel(Long userId, Long scheduleId, String queueToken) {
        if (queueToken == null || queueToken.isBlank()
                || !withAvailableQueue(() -> queueAdmissionStore.cancel(userId, scheduleId, queueToken))) {
            throw new QueueAdmissionRequiredException();
        }
    }

    private <T> T withAvailableQueue(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException exception) {
            throw new QueueTemporarilyUnavailableException(exception);
        }
    }

    private void withAvailableQueue(Runnable operation) {
        withAvailableQueue(() -> {
            operation.run();
            return null;
        });
    }

    private QueueMode effectiveQueueMode() {
        return autoActivationManager.resolve(featureFlagService.getQueueModeForRuntime());
    }
}
