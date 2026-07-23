package com.jipi.ticket_ledger.user.application.model;

public record ResponseMeDTO(
        Long id,
        String email,
        String name,
        String role,
        String status
) {
}
