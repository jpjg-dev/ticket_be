package com.jipi.ticket_ledger.seat.application.model;

import java.util.List;

public record SeatListResponse(
        Long scheduleId,
        boolean soldOut,
        List<SeatResponse> seats
) {
}
