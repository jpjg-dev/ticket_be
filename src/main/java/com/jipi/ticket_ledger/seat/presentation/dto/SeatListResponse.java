package com.jipi.ticket_ledger.seat.presentation.dto;

import java.util.List;

public record SeatListResponse(
        Long scheduleId,
        boolean soldOut,
        List<SeatResponse> seats
) {
}
