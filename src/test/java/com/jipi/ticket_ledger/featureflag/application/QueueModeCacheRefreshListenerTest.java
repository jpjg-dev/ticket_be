package com.jipi.ticket_ledger.featureflag.application;

import com.jipi.ticket_ledger.featureflag.domain.QueueMode;
import com.jipi.ticket_ledger.featureflag.domain.QueueModeSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doThrow;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@ExtendWith(MockitoExtension.class)
class QueueModeCacheRefreshListenerTest {

    @Mock
    private QueueModeCache queueModeCache;

    @Test
    @DisplayName("커밋 후 cache 갱신 실패는 트랜잭션 결과를 되돌리지 않는다")
    void isolatesCacheRefreshFailure() {
        QueueModeSnapshot snapshot = new QueueModeSnapshot(QueueMode.ENFORCED, 1L);
        doThrow(new QueueModeCacheAccessException(new IllegalStateException("redis down")))
                .when(queueModeCache).putIfNewer(snapshot);
        QueueModeCacheRefreshListener listener = new QueueModeCacheRefreshListener(queueModeCache);

        assertDoesNotThrow(() -> listener.refreshCache(new QueueModeChangedEvent(snapshot)));
    }
}
