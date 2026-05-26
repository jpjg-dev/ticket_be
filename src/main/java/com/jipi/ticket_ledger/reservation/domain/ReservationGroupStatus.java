package com.jipi.ticket_ledger.reservation.domain;

public enum ReservationGroupStatus {
    PENDING, // 결제 전 선점 상태
    CONFIRMED, // 결제 승인 및 예매 확정
    CANCELED, // 결제 취소 및 예매 취소
    EXPIRED // 선점 만료
}
