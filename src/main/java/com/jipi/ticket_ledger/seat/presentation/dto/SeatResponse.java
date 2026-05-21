package com.jipi.ticket_ledger.seat.presentation.dto;

public record SeatResponse(
        Long id,
        Long scheduleId,
        String seatNumber,
        String grade,
        Integer price,
        String status
) {
}
