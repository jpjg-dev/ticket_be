package com.jipi.ticket_ledger.seat.presentation.dto;

public record SeatAvailabilityResponse(
        Long scheduleId,
        boolean soldOut,
        long available,
        long held,
        long booked
) {
}
