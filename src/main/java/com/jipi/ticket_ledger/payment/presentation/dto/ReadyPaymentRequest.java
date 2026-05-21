package com.jipi.ticket_ledger.payment.presentation.dto;

import jakarta.validation.constraints.NotNull;

public record ReadyPaymentRequest(
        @NotNull(message = "예매 묶음 ID는 필수입니다.")
        Long reservationGroupId
) {
}
