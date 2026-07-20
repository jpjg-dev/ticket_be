package com.jipi.ticket_ledger.queue.presentation.dto;

import com.jipi.ticket_ledger.queue.domain.QueueAdmissionSnapshot;
import com.jipi.ticket_ledger.queue.domain.QueueAdmissionStatus;

public record QueueAdmissionResponse(
        QueueAdmissionStatus status,
        String queueToken,
        Long position,
        Long estimatedWaitSeconds
) {
    public static QueueAdmissionResponse from(QueueAdmissionSnapshot snapshot) {
        return new QueueAdmissionResponse(
                snapshot.status(), snapshot.queueToken(), snapshot.position(), snapshot.estimatedWaitSeconds()
        );
    }
}
