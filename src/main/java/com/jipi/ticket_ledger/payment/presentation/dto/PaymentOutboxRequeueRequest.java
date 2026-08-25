package com.jipi.ticket_ledger.payment.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PaymentOutboxRequeueRequest(
        @NotBlank
        @Size(max = 200)
        String reason
) {
}
