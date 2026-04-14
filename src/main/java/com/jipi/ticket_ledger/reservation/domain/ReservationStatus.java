package com.jipi.ticket_ledger.reservation.domain;

public enum ReservationStatus {
    PENDING, // 대기중
    CONFIRMED, // 확인중
    CANCELED, // 취소됨
    EXPIRED // 만료됨
}
