package com.jipi.ticket_ledger.global.exception;

public class CacheTemporarilyUnavailableException extends RuntimeException {

    private final long retryAfterSeconds;

    public CacheTemporarilyUnavailableException(long retryAfterSeconds) {
        super("공연 조회가 일시적으로 혼잡합니다.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
