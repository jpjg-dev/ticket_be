package com.jipi.ticket_ledger.seat.application.model;

public record SeatAvailabilityResponse(
        Long scheduleId,
        boolean soldOut,
        long available,
        long held,
        long booked
) {
}
