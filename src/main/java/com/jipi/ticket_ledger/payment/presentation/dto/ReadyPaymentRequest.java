package com.jipi.ticket_ledger.payment.presentation.dto;

import jakarta.validation.constraints.NotNull;

public record ReadyPaymentRequest(
        @NotNull(message = "예약 ID는 필수입니다.")
        Long reservationId
) {
}
