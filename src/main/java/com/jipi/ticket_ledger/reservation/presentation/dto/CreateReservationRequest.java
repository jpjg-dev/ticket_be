package com.jipi.ticket_ledger.reservation.presentation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateReservationRequest(
        @NotNull @Positive Long scheduleId,
        String queueToken,
        @NotEmpty(message = "Seat IDs must not be empty")
        @Size(max = 2, message = "Seats can be reserved up to 2")
        List<Long> seatIds
) {
}
