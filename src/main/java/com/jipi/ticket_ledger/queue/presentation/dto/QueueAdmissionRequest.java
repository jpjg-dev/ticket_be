package com.jipi.ticket_ledger.queue.presentation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record QueueAdmissionRequest(
        @NotNull @Positive Long scheduleId
) {
}
