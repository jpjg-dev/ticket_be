package com.jipi.ticket_ledger.reservation.application.lock;

public class SeatLockBusyException extends IllegalStateException {

    public SeatLockBusyException() {
        super("다른 사용자가 선택 중인 좌석입니다. 잠시 후 다시 시도해주세요.");
    }
}
