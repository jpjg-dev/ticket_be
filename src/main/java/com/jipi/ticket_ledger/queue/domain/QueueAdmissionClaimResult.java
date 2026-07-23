package com.jipi.ticket_ledger.queue.domain;

public enum QueueAdmissionClaimResult {
    CLAIMED,
    INVALID_OWNER,
    ADMISSION_UNAVAILABLE,
    ALREADY_CLAIMED
}
