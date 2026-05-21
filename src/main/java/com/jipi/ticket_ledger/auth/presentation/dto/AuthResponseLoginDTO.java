package com.jipi.ticket_ledger.auth.presentation.dto;

public record AuthResponseLoginDTO(String accessToken,
                                   String refreshToken) {
}
