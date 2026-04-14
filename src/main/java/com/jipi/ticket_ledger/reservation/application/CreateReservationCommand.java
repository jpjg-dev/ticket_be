package com.jipi.ticket_ledger.reservation.application;

public record CreateReservationCommand(Long userId,
                                       Long seatId) {
}
