package com.jipi.ticket_ledger.queue.application;

import com.jipi.ticket_ledger.featureflag.application.FeatureFlagService;
import com.jipi.ticket_ledger.featureflag.domain.QueueMode;
import com.jipi.ticket_ledger.queue.domain.QueueAdmissionSnapshot;
import com.jipi.ticket_ledger.queue.domain.QueueAdmissionClaimResult;
import com.jipi.ticket_ledger.queue.domain.QueueAdmissionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueueAdmissionServiceTest {

    @Mock
    private FeatureFlagService featureFlagService;

    @Mock
    private QueueAdmissionStore queueAdmissionStore;

    @Mock
    private QueueAdmissionMetrics metrics;

    @InjectMocks
    private QueueAdmissionService queueAdmissionService;

    @Test
    void offModeBypassesQueue() {
        when(featureFlagService.getQueueModeForRuntime()).thenReturn(QueueMode.OFF);

        QueueAdmissionSnapshot result = queueAdmissionService.enter(1L, 10L);

        assertEquals(QueueAdmissionStatus.BYPASSED, result.status());
        verify(queueAdmissionStore, never()).register(1L, 10L);
    }

    @Test
    void shadowModeObservesWithoutRegisteringUser() {
        when(featureFlagService.getQueueModeForRuntime()).thenReturn(QueueMode.SHADOW);
        when(queueAdmissionStore.countWaiting(10L)).thenReturn(3L);

        QueueAdmissionSnapshot result = queueAdmissionService.enter(1L, 10L);

        assertEquals(QueueAdmissionStatus.BYPASSED, result.status());
        verify(metrics).record("SHADOW", "would_wait");
        verify(queueAdmissionStore, never()).register(1L, 10L);
    }

    @Test
    void enforcedModeRegistersAndReturnsPosition() {
        QueueAdmissionSnapshot waiting = QueueAdmissionSnapshot.waiting("token", 2L, 3L);
        when(featureFlagService.getQueueModeForRuntime()).thenReturn(QueueMode.ENFORCED);
        when(queueAdmissionStore.register(1L, 10L)).thenReturn("token");
        when(queueAdmissionStore.getStatus(1L, 10L, "token")).thenReturn(waiting);

        QueueAdmissionSnapshot result = queueAdmissionService.enter(1L, 10L);

        assertEquals(waiting, result);
        verify(metrics).record("ENFORCED", "waiting");
    }

    @Test
    void enforcedModeRejectsReservationWithoutAdmittedToken() {
        when(featureFlagService.getQueueModeForRuntime()).thenReturn(QueueMode.ENFORCED);
        when(queueAdmissionStore.claim(1L, 10L, "token"))
                .thenReturn(QueueAdmissionClaimResult.ADMISSION_UNAVAILABLE);

        assertThrows(
                QueueAdmissionRequiredException.class,
                () -> queueAdmissionService.claimForReservation(1L, 10L, "token")
        );
        verify(metrics).recordToken("admission_unavailable");
    }

    @Test
    void enforcedModeRejectsDuplicateClaim() {
        when(featureFlagService.getQueueModeForRuntime()).thenReturn(QueueMode.ENFORCED);
        when(queueAdmissionStore.claim(1L, 10L, "token"))
                .thenReturn(QueueAdmissionClaimResult.ALREADY_CLAIMED);

        assertThrows(
                QueueAdmissionRequiredException.class,
                () -> queueAdmissionService.claimForReservation(1L, 10L, "token")
        );

        verify(metrics).recordToken("already_claimed");
    }

    @Test
    void offTransitionLetsExistingWaiterBypassQueue() {
        when(featureFlagService.getQueueModeForRuntime()).thenReturn(QueueMode.OFF);

        QueueAdmissionSnapshot result = queueAdmissionService.getStatus(1L, 10L, "existing-token");

        assertEquals(QueueAdmissionStatus.BYPASSED, result.status());
        verify(queueAdmissionStore, never()).getStatus(1L, 10L, "existing-token");
    }

    @Test
    void enforcedModeConvertsRedisTimeoutToServiceUnavailable() {
        when(featureFlagService.getQueueModeForRuntime()).thenReturn(QueueMode.ENFORCED);
        when(queueAdmissionStore.register(1L, 10L)).thenThrow(new QueryTimeoutException("redis timeout"));

        assertThrows(
                QueueTemporarilyUnavailableException.class,
                () -> queueAdmissionService.enter(1L, 10L)
        );
    }

    @Test
    void cancelRemovesOwnedQueueEntry() {
        when(queueAdmissionStore.cancel(1L, 10L, "token")).thenReturn(true);

        queueAdmissionService.cancel(1L, 10L, "token");

        verify(queueAdmissionStore).cancel(1L, 10L, "token");
    }

    @Test
    void cancelRejectsUnknownOrClaimedQueueEntry() {
        when(queueAdmissionStore.cancel(1L, 10L, "token")).thenReturn(false);

        assertThrows(
                QueueAdmissionRequiredException.class,
                () -> queueAdmissionService.cancel(1L, 10L, "token")
        );
    }
}
