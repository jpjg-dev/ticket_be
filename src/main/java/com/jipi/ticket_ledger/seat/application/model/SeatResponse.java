package com.jipi.ticket_ledger.seat.application.model;

public record SeatResponse(
        Long id,
        String seatNumber,
        String grade,
        Integer price,
        String status
) {
}
