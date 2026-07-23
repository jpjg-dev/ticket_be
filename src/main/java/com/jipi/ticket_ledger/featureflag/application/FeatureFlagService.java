package com.jipi.ticket_ledger.featureflag.application;

import com.jipi.ticket_ledger.featureflag.domain.QueueFeatureFlag;
import com.jipi.ticket_ledger.featureflag.domain.QueueFeatureFlagRepository;
import com.jipi.ticket_ledger.featureflag.domain.QueueMode;
import com.jipi.ticket_ledger.featureflag.domain.QueueModeSnapshot;
import com.jipi.ticket_ledger.global.log.LogEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureFlagService {

    private final QueueFeatureFlagRepository queueFeatureFlagRepository;
    private final QueueModeCache queueModeCache;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Transactional(readOnly = true)
    public QueueModeSnapshot getCurrentQueueMode() {
        return getQueueFeatureFlag().snapshot();
    }

    /**
     * Queue admission checks must never rely on a DB fallback while Redis is unavailable.
     */
    public QueueMode getQueueModeForRuntime() {
        try {
            return queueModeCache.findQueueMode()
                    .orElseGet(this::loadCurrentQueueModeAndWarmCache)
                    .queueMode();
        } catch (QueueModeCacheAccessException exception) {
            log.warn("event={} flag=queueMode fallback=ENFORCED reason=redis_access_failure",
                    LogEvents.FEATURE_FLAG_QUEUE_MODE_RUNTIME_FALLBACK_ENFORCED);
            return QueueMode.ENFORCED;
        }
    }

    @Transactional
    public QueueModeSnapshot updateQueueMode(QueueMode queueMode, long expectedVersion) {
        int updatedCount = queueFeatureFlagRepository.updateModeIfVersionMatches(
                QueueFeatureFlag.SINGLETON_ID,
                queueMode,
                clock.instant(),
                expectedVersion
        );
        if (updatedCount != 1) {
            throw new FeatureFlagVersionConflictException();
        }

        QueueModeSnapshot current = getQueueFeatureFlag().snapshot();
        eventPublisher.publishEvent(new QueueModeChangedEvent(current));
        return current;
    }

    private QueueModeSnapshot loadCurrentQueueModeAndWarmCache() {
        QueueModeSnapshot databaseSnapshot = getCurrentQueueMode();
        if (queueModeCache.putIfNewer(databaseSnapshot)) {
            return databaseSnapshot;
        }
        return queueModeCache.findQueueMode().orElseThrow(QueueModeCacheAccessException::new);
    }

    private QueueFeatureFlag getQueueFeatureFlag() {
        return queueFeatureFlagRepository.findById(QueueFeatureFlag.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("Queue feature flag is not initialized"));
    }
}
