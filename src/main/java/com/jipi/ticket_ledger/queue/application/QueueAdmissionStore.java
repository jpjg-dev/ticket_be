package com.jipi.ticket_ledger.queue.application;

import com.jipi.ticket_ledger.queue.domain.QueueAdmissionSnapshot;
import com.jipi.ticket_ledger.queue.domain.QueueAdmissionClaimResult;

public interface QueueAdmissionStore {
    String register(Long userId, Long scheduleId);

    QueueAdmissionSnapshot getStatus(Long userId, Long scheduleId, String queueToken);

    QueueAdmissionClaimResult claim(Long userId, Long scheduleId, String queueToken);

    void release(Long userId, Long scheduleId, String queueToken);

    void complete(Long userId, Long scheduleId, String queueToken);

    boolean cancel(Long userId, Long scheduleId, String queueToken);

    int admitNextForActiveSchedules();

    long countWaiting(Long scheduleId);

    long countTotalWaiting();
}
