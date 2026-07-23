package com.jipi.ticket_ledger.reservation.infrastructure;

import com.jipi.ticket_ledger.reservation.application.lock.SeatLockBusyException;
import com.jipi.ticket_ledger.reservation.application.lock.SeatLockHandle;
import com.jipi.ticket_ledger.reservation.application.lock.SeatLockInfrastructureException;
import com.jipi.ticket_ledger.reservation.application.lock.SeatLockManager;
import com.jipi.ticket_ledger.reservation.application.lock.SeatLockPolicyProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class RedisSeatLockManager implements SeatLockManager {

    private static final String KEY_PREFIX = "ticketledger:lock:seat:";

    private final RedissonClient redissonClient;
    private final CircuitBreaker seatLockRedisCircuitBreaker;
    private final SeatLockPolicyProperties properties;

    public RedisSeatLockManager(
            @Lazy RedissonClient redissonClient,
            CircuitBreaker seatLockRedisCircuitBreaker,
            SeatLockPolicyProperties properties
    ) {
        this.redissonClient = redissonClient;
        this.seatLockRedisCircuitBreaker = seatLockRedisCircuitBreaker;
        this.properties = properties;
    }

    @Override
    public SeatLockHandle acquire(List<Long> seatIds) {
        try {
            return seatLockRedisCircuitBreaker.executeSupplier(() -> acquireLocks(seatIds));
        } catch (SeatLockBusyException exception) {
            throw exception;
        } catch (CallNotPermittedException exception) {
            throw new SeatLockInfrastructureException(exception, true);
        } catch (SeatLockInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SeatLockInfrastructureException(exception, true);
        }
    }

    private SeatLockHandle acquireLocks(List<Long> seatIds) {
        List<Long> sortedSeatIds = seatIds.stream().distinct().sorted().toList();
        List<RLock> acquiredLocks = new ArrayList<>(sortedSeatIds.size());

        try {
            for (Long seatId : sortedSeatIds) {
                RLock lock = redissonClient.getLock(KEY_PREFIX + seatId);
                if (!tryAcquire(lock)) {
                    if (!releaseInReverse(acquiredLocks)) {
                        throw new SeatLockInfrastructureException(
                                new IllegalStateException("Failed to release partially acquired seat locks."),
                                false
                        );
                    }
                    throw new SeatLockBusyException();
                }
                acquiredLocks.add(lock);
            }
            return () -> releaseInReverse(acquiredLocks);
        } catch (SeatLockBusyException exception) {
            throw exception;
        } catch (SeatLockInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            boolean fallbackSafe = acquiredLocks.isEmpty();
            releaseInReverse(acquiredLocks);
            throw new SeatLockInfrastructureException(exception, fallbackSafe);
        }
    }

    private boolean tryAcquire(RLock lock) {
        try {
            // Omitting leaseTime enables Redisson's watchdog renewal while this owner is alive.
            return lock.tryLock(properties.waitTime().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SeatLockBusyException();
        }
    }

    private boolean releaseInReverse(List<RLock> acquiredLocks) {
        List<RLock> reverseOrder = new ArrayList<>(acquiredLocks);
        Collections.reverse(reverseOrder);
        boolean released = true;

        for (RLock lock : reverseOrder) {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (RuntimeException exception) {
                released = false;
                // DB work has either committed or rolled back. Ownership timeout is the final cleanup path.
                log.warn("Failed to release Redis seat lock. lockName={}", lock.getName(), exception);
            }
        }
        return released;
    }
}
