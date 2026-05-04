package com.jipi.ticket_ledger.auth.presentation.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record AuthRequestLoginDTO(
        @NotBlank
        @Email
        String email,
        @NotBlank
        String password
) {
}
