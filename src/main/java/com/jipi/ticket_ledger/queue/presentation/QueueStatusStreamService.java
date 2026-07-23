package com.jipi.ticket_ledger.queue.presentation;

import com.jipi.ticket_ledger.queue.application.QueueAdmissionService;
import com.jipi.ticket_ledger.queue.application.QueueAdmissionRequiredException;
import com.jipi.ticket_ledger.queue.application.QueueAdmissionMetrics;
import com.jipi.ticket_ledger.queue.domain.QueueAdmissionStatus;
import com.jipi.ticket_ledger.queue.infrastructure.QueueAdmissionProperties;
import com.jipi.ticket_ledger.queue.presentation.dto.QueueAdmissionResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class QueueStatusStreamService {

    private final QueueAdmissionService queueAdmissionService;
    private final QueueAdmissionProperties properties;
    private final ExecutorService queueStatusExecutor;
    private final QueueAdmissionMetrics metrics;

    public QueueStatusStreamService(
            QueueAdmissionService queueAdmissionService,
            QueueAdmissionProperties properties,
            @Qualifier("queueStatusExecutor") ExecutorService queueStatusExecutor,
            QueueAdmissionMetrics metrics
    ) {
        this.queueAdmissionService = queueAdmissionService;
        this.properties = properties;
        this.queueStatusExecutor = queueStatusExecutor;
        this.metrics = metrics;
    }

    public SseEmitter open(Long userId, Long scheduleId, String queueToken) {
        SseEmitter emitter = new SseEmitter(properties.streamTimeout().toMillis());
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicReference<Future<?>> taskReference = new AtomicReference<>();
        long openedAt = System.nanoTime();
        metrics.streamOpened();

        Future<?> task = queueStatusExecutor.submit(() -> {
            try {
                while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                    Thread.sleep(properties.streamInterval());
                    QueueAdmissionResponse response = QueueAdmissionResponse.from(
                            queueAdmissionService.getStatus(userId, scheduleId, queueToken)
                    );
                    emitter.send(SseEmitter.event().name("queue-status").data(response));
                    if (response.status() != QueueAdmissionStatus.WAITING) {
                        if (response.status() == QueueAdmissionStatus.ADMITTED) {
                            metrics.recordObservedWait(Duration.ofNanos(System.nanoTime() - openedAt));
                        }
                        emitter.complete();
                        return;
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (QueueAdmissionRequiredException exception) {
                emitter.complete();
            } catch (IOException exception) {
                emitter.completeWithError(exception);
            } catch (RuntimeException exception) {
                emitter.completeWithError(exception);
            } finally {
                if (closed.compareAndSet(false, true)) {
                    metrics.streamClosed();
                }
            }
        });
        taskReference.set(task);

        Runnable cancel = () -> {
            if (closed.compareAndSet(false, true)) {
                metrics.streamClosed();
                Future<?> streamTask = taskReference.get();
                if (streamTask != null) {
                    streamTask.cancel(true);
                }
            }
        };
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancel);
        emitter.onError(ignored -> cancel.run());
        return emitter;
    }
}
