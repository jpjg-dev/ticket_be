package com.jipi.ticket_ledger.seat.domain;

public enum SeatStatus {
    AVAILABLE, //예약 가능
    HELD, // 예약 중 (일시적으로 보류된 상태)
    BOOKED // 예약 됨
}
