package com.jipi.ticket_ledger.featureflag.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.jipi.ticket_ledger.global.log.LogEvents;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueModeCacheRefreshListener {

    private final QueueModeCache queueModeCache;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void refreshCache(QueueModeChangedEvent event) {
        log.info("event={} flag=queueMode newMode={} version={}",
                LogEvents.FEATURE_FLAG_QUEUE_MODE_CHANGED,
                event.current().queueMode(), event.current().version());

        try {
            queueModeCache.putIfNewer(event.current());
        } catch (QueueModeCacheAccessException exception) {
            log.warn("event={} flag=queueMode intendedMode={} version={} fallback=ENFORCED",
                    LogEvents.FEATURE_FLAG_QUEUE_MODE_CACHE_REFRESH_FAILED,
                    event.current().queueMode(), event.current().version());
        }
    }
}
