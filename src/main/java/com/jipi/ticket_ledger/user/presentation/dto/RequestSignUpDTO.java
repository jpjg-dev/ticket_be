package com.jipi.ticket_ledger.user.presentation.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record RequestSignUpDTO(
            @NotBlank
            @Email
            String email,
            @NotBlank
            String name,
            @NotBlank
            String password) {
    }