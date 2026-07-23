package com.jipi.ticket_ledger.reservation.application.lock;

public class SeatLockInfrastructureException extends RuntimeException {

    private final boolean fallbackSafe;

    public SeatLockInfrastructureException(Throwable cause, boolean fallbackSafe) {
        super("좌석 잠금 저장소를 사용할 수 없습니다.", cause);
        this.fallbackSafe = fallbackSafe;
    }

    public boolean isFallbackSafe() {
        return fallbackSafe;
    }
}
