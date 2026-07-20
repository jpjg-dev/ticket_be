package com.jipi.ticket_ledger.reservation.application.lock;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class SeatLockFallbackGuard {

    private final Semaphore permits;
    private final SeatLockPolicyProperties properties;

    public SeatLockFallbackGuard(SeatLockPolicyProperties properties) {
        this.properties = properties;
        this.permits = new Semaphore(properties.fallbackMaxConcurrent(), true);
    }

    public <T> T execute(Supplier<T> operation) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(
                    properties.fallbackWaitTime().toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SeatLockTemporarilyUnavailableException(properties.retryAfterSeconds());
        }

        if (!acquired) {
            throw new SeatLockTemporarilyUnavailableException(properties.retryAfterSeconds());
        }

        try {
            return operation.get();
        } finally {
            permits.release();
        }
    }
}
