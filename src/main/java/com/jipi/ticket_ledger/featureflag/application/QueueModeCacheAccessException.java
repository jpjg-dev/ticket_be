package com.jipi.ticket_ledger.featureflag.application;

public class QueueModeCacheAccessException extends RuntimeException {

    public QueueModeCacheAccessException() {
        super("Queue mode cache state is unavailable");
    }

    public QueueModeCacheAccessException(Throwable cause) {
        super(cause);
    }
}
