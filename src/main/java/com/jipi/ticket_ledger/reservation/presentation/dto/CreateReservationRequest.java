package com.jipi.ticket_ledger.reservation.presentation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateReservationRequest(
        @NotEmpty(message = "Seat IDs must not be empty")
        @Size(max = 2, message = "Seats can be reserved up to 2")
        List<Long> seatIds
) {
}
