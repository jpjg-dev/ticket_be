package com.jipi.ticket_ledger.queue.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueueAdmissionSchedulerTest {

    @Mock
    private QueueAdmissionStore queueAdmissionStore;

    @Mock
    private QueueAdmissionMetrics metrics;

    @InjectMocks
    private QueueAdmissionScheduler scheduler;

    @Test
    void recordsAdmittedThroughputAndRemainingBacklog() {
        when(queueAdmissionStore.admitNextForActiveSchedules()).thenReturn(20);
        when(queueAdmissionStore.countTotalWaiting()).thenReturn(980L);

        scheduler.admitNext();

        verify(metrics).recordAdmitted(20);
        verify(metrics).setWaitingUsers(980L);
    }
}
