package com.jipi.ticket_ledger.payment.presentation.dto;

public record FailRedirectRequest(
        String orderId,
        String code,
        String message
) {
}
