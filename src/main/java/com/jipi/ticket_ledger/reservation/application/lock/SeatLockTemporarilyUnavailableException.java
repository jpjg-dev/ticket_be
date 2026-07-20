package com.jipi.ticket_ledger.reservation.application.lock;

public class SeatLockTemporarilyUnavailableException extends RuntimeException {

    private final long retryAfterSeconds;

    public SeatLockTemporarilyUnavailableException(long retryAfterSeconds) {
        super("좌석 예약 요청이 몰리고 있습니다. 잠시 후 다시 시도해주세요.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
