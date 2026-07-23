package com.jipi.ticket_ledger.queue.application;

public record QueueLoadSnapshot(
        double requestRate,
        int concurrentRequests,
        double processCpu,
        double tomcatBusyRatio,
        int hikariPending,
        long waitingUsers,
        boolean telemetryComplete
) {
}
