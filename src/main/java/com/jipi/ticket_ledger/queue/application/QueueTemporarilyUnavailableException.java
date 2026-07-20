package com.jipi.ticket_ledger.queue.application;

public class QueueTemporarilyUnavailableException extends RuntimeException {

    private static final long RETRY_AFTER_SECONDS = 1L;

    public QueueTemporarilyUnavailableException(Throwable cause) {
        super("대기열을 일시적으로 사용할 수 없습니다.", cause);
    }

    public long getRetryAfterSeconds() {
        return RETRY_AFTER_SECONDS;
    }
}
