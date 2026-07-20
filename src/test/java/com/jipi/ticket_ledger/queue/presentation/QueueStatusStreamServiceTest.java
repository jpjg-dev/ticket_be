package com.jipi.ticket_ledger.queue.presentation;

import com.jipi.ticket_ledger.queue.application.QueueAdmissionMetrics;
import com.jipi.ticket_ledger.queue.application.QueueAdmissionService;
import com.jipi.ticket_ledger.queue.domain.QueueAdmissionSnapshot;
import com.jipi.ticket_ledger.queue.infrastructure.QueueAdmissionProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueueStatusStreamServiceTest {

    @Mock
    private QueueAdmissionService queueAdmissionService;

    @Mock
    private QueueAdmissionMetrics metrics;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    @Test
    void closesSseResourcesAfterAdmission() {
        QueueAdmissionProperties properties = new QueueAdmissionProperties(
                20, 3000, Duration.ofMinutes(15), Duration.ofMinutes(2),
                Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofMillis(1)
        );
        when(queueAdmissionService.getStatus(1L, 10L, "token"))
                .thenReturn(QueueAdmissionSnapshot.admitted("token"));
        QueueStatusStreamService service =
                new QueueStatusStreamService(queueAdmissionService, properties, executor, metrics);

        service.open(1L, 10L, "token");

        verify(metrics).streamOpened();
        verify(metrics, timeout(1000)).recordObservedWait(any(Duration.class));
        verify(metrics, timeout(1000)).streamClosed();
    }
}
