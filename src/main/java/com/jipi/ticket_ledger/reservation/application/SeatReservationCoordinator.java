package com.jipi.ticket_ledger.reservation.application;

import com.jipi.ticket_ledger.reservation.application.lock.SeatLockBusyException;
import com.jipi.ticket_ledger.reservation.application.lock.SeatLockFallbackGuard;
import com.jipi.ticket_ledger.reservation.application.lock.SeatLockHandle;
import com.jipi.ticket_ledger.reservation.application.lock.SeatLockInfrastructureException;
import com.jipi.ticket_ledger.reservation.application.lock.SeatLockManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SeatReservationCoordinator {

    private final SeatLockManager seatLockManager;
    private final SeatLockFallbackGuard fallbackGuard;
    private final ReservationService reservationService;

    public Long createReservation(Long userId, Long scheduleId, List<Long> seatIds) {
        try {
            return createWithRedisLock(userId, scheduleId, seatIds);
        } catch (SeatLockInfrastructureException exception) {
            if (!exception.isFallbackSafe()) {
                throw exception;
            }

            log.warn("Redis seat lock unavailable. Falling back to bounded database lock. userId={} scheduleId={}",
                    userId, scheduleId);
            return fallbackGuard.execute(
                    () -> reservationService.createReservationWithPessimisticLock(userId, scheduleId, seatIds)
            );
        }
    }

    private Long createWithRedisLock(Long userId, Long scheduleId, List<Long> seatIds) {
        try (SeatLockHandle ignored = seatLockManager.acquire(seatIds)) {
            try {
                // The transactional proxy flushes and commits before this method returns and the lock is closed.
                return reservationService.createReservation(userId, scheduleId, seatIds);
            } catch (ObjectOptimisticLockingFailureException exception) {
                // A lost/expired Redis lock allowed another writer to commit first. Retrying adds contention.
                throw new SeatLockBusyException();
            }
        }
    }
}
