package com.jipi.ticket_ledger.reservation.application;

import com.jipi.ticket_ledger.reservation.application.lock.SeatLockBusyException;
import com.jipi.ticket_ledger.reservation.application.lock.SeatLockFallbackGuard;
import com.jipi.ticket_ledger.reservation.application.lock.SeatLockHandle;
import com.jipi.ticket_ledger.reservation.application.lock.SeatLockInfrastructureException;
import com.jipi.ticket_ledger.reservation.application.lock.SeatLockManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeatReservationCoordinatorTest {

    @Mock
    private SeatLockManager seatLockManager;

    @Mock
    private SeatLockFallbackGuard fallbackGuard;

    @Mock
    private ReservationService reservationService;

    @Mock
    private SeatLockHandle lockHandle;

    @Test
    void releasesRedisLockAfterTransactionalServiceReturns() {
        SeatReservationCoordinator coordinator = coordinator();
        when(seatLockManager.acquire(List.of(20L, 10L))).thenReturn(lockHandle);
        when(reservationService.createReservation(1L, 5L, List.of(20L, 10L))).thenReturn(77L);

        Long result = coordinator.createReservation(1L, 5L, List.of(20L, 10L));

        assertEquals(77L, result);
        InOrder order = inOrder(reservationService, lockHandle);
        order.verify(reservationService).createReservation(1L, 5L, List.of(20L, 10L));
        order.verify(lockHandle).close();
    }

    @Test
    void convertsOptimisticConflictToImmediateSeatConflictWithoutRetry() {
        SeatReservationCoordinator coordinator = coordinator();
        when(seatLockManager.acquire(List.of(10L))).thenReturn(lockHandle);
        when(reservationService.createReservation(1L, 5L, List.of(10L)))
                .thenThrow(new ObjectOptimisticLockingFailureException("Seat", 10L));

        assertThrows(
                SeatLockBusyException.class,
                () -> coordinator.createReservation(1L, 5L, List.of(10L))
        );

        verify(reservationService).createReservation(1L, 5L, List.of(10L));
        verify(reservationService, never()).createReservationWithPessimisticLock(any(), any(), any());
        verify(lockHandle).close();
    }

    @Test
    void usesBoundedPessimisticFallbackWhenRedisFailedBeforeAnyLockWasAcquired() {
        SeatReservationCoordinator coordinator = coordinator();
        when(seatLockManager.acquire(List.of(10L)))
                .thenThrow(new SeatLockInfrastructureException(new RuntimeException("redis down"), true));
        when(fallbackGuard.execute(any())).thenAnswer(invocation -> {
            java.util.function.Supplier<Long> operation = invocation.getArgument(0);
            return operation.get();
        });
        when(reservationService.createReservationWithPessimisticLock(1L, 5L, List.of(10L))).thenReturn(77L);

        Long result = coordinator.createReservation(1L, 5L, List.of(10L));

        assertEquals(77L, result);
        verify(reservationService).createReservationWithPessimisticLock(1L, 5L, List.of(10L));
        verify(reservationService, never()).createReservation(any(), any(), any());
    }

    @Test
    void failsClosedWhenRedisFailedAfterASeatLockWasAcquired() {
        SeatReservationCoordinator coordinator = coordinator();
        SeatLockInfrastructureException failure =
                new SeatLockInfrastructureException(new RuntimeException("ambiguous ownership"), false);
        when(seatLockManager.acquire(List.of(10L, 20L))).thenThrow(failure);

        SeatLockInfrastructureException result = assertThrows(
                SeatLockInfrastructureException.class,
                () -> coordinator.createReservation(1L, 5L, List.of(10L, 20L))
        );

        assertEquals(failure, result);
        verify(fallbackGuard, never()).execute(any());
        verify(reservationService, never()).createReservation(any(), any(), any());
        verify(reservationService, never()).createReservationWithPessimisticLock(any(), any(), any());
    }

    private SeatReservationCoordinator coordinator() {
        return new SeatReservationCoordinator(seatLockManager, fallbackGuard, reservationService);
    }
}
