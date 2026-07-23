package com.jipi.ticket_ledger.queue.application;

public record QueueAdmissionPermit(
        boolean enforced,
        Long userId,
        Long scheduleId,
        String queueToken
) {
    public static QueueAdmissionPermit bypassed(Long userId, Long scheduleId) {
        return new QueueAdmissionPermit(false, userId, scheduleId, null);
    }

    public static QueueAdmissionPermit claimed(Long userId, Long scheduleId, String queueToken) {
        return new QueueAdmissionPermit(true, userId, scheduleId, queueToken);
    }
}
