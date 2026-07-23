package com.jipi.ticket_ledger.featureflag.presentation.dto;

import com.jipi.ticket_ledger.featureflag.domain.QueueMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdateQueueModeRequest(
        @NotNull QueueMode mode,
        @NotNull @PositiveOrZero Long expectedVersion
) {
}
