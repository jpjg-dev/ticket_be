package com.jipi.ticket_ledger.user.presentation.dto;

public record ResponseMeDTO(
        Long id,
        String email,
        String name,
        String role,
        String status
) {
}