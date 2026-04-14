package com.jipi.ticket_ledger.reservation.presentation.dto;

import jakarta.validation.constraints.NotNull;

public record CreateReservationRequest(@NotNull(message = "User ID must not be null") Long userId,
                                       @NotNull(message = "Seat ID must not be null") Long seatId) {
}
