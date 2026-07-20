package com.jipi.ticket_ledger.queue.domain;

public record QueueAdmissionSnapshot(
        QueueAdmissionStatus status,
        String queueToken,
        Long position,
        Long estimatedWaitSeconds
) {
    public static QueueAdmissionSnapshot bypassed() {
        return new QueueAdmissionSnapshot(QueueAdmissionStatus.BYPASSED, null, null, null);
    }

    public static QueueAdmissionSnapshot waiting(String queueToken, long position, long estimatedWaitSeconds) {
        return new QueueAdmissionSnapshot(QueueAdmissionStatus.WAITING, queueToken, position, estimatedWaitSeconds);
    }

    public static QueueAdmissionSnapshot admitted(String queueToken) {
        return new QueueAdmissionSnapshot(QueueAdmissionStatus.ADMITTED, queueToken, 0L, 0L);
    }
}
