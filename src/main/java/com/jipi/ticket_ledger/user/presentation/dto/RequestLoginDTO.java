package com.jipi.ticket_ledger.user.presentation.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record RequestLoginDTO(
        @NotBlank
        @Email
        String email,
        @NotBlank
        String password
) {
}
