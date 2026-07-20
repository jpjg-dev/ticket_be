package com.jipi.ticket_ledger.reservation.infrastructure;

import com.jipi.ticket_ledger.reservation.application.lock.SeatLockBusyException;
import com.jipi.ticket_ledger.reservation.application.lock.SeatLockHandle;
import com.jipi.ticket_ledger.reservation.application.lock.SeatLockInfrastructureException;
import com.jipi.ticket_ledger.reservation.application.lock.SeatLockPolicyProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisSeatLockManagerTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock seat10Lock;

    @Mock
    private RLock seat20Lock;

    private RedisSeatLockManager seatLockManager;

    @BeforeEach
    void setUp() {
        SeatLockPolicyProperties properties = new SeatLockPolicyProperties(
                Duration.ZERO,
                Duration.ofSeconds(30),
                2,
                Duration.ofMillis(100),
                1
        );
        seatLockManager = new RedisSeatLockManager(
                redissonClient,
                CircuitBreaker.ofDefaults("seat-lock-test"),
                properties
        );
    }

    @Test
    void acquiresSortedSeatLocksAndReleasesThemInReverseOrder() throws InterruptedException {
        when(redissonClient.getLock("ticketledger:lock:seat:10")).thenReturn(seat10Lock);
        when(redissonClient.getLock("ticketledger:lock:seat:20")).thenReturn(seat20Lock);
        when(seat10Lock.tryLock(0, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(seat20Lock.tryLock(0, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(seat10Lock.isHeldByCurrentThread()).thenReturn(true);
        when(seat20Lock.isHeldByCurrentThread()).thenReturn(true);

        SeatLockHandle handle = seatLockManager.acquire(List.of(20L, 10L, 20L));
        handle.close();

        InOrder acquisitionOrder = inOrder(redissonClient, seat10Lock, seat20Lock);
        acquisitionOrder.verify(redissonClient).getLock("ticketledger:lock:seat:10");
        acquisitionOrder.verify(seat10Lock).tryLock(0, TimeUnit.MILLISECONDS);
        acquisitionOrder.verify(redissonClient).getLock("ticketledger:lock:seat:20");
        acquisitionOrder.verify(seat20Lock).tryLock(0, TimeUnit.MILLISECONDS);

        InOrder releaseOrder = inOrder(seat20Lock, seat10Lock);
        releaseOrder.verify(seat20Lock).isHeldByCurrentThread();
        releaseOrder.verify(seat20Lock).unlock();
        releaseOrder.verify(seat10Lock).isHeldByCurrentThread();
        releaseOrder.verify(seat10Lock).unlock();
    }

    @Test
    void rejectsImmediatelyWhenASeatLockIsAlreadyHeld() throws InterruptedException {
        when(redissonClient.getLock("ticketledger:lock:seat:10")).thenReturn(seat10Lock);
        when(seat10Lock.tryLock(0, TimeUnit.MILLISECONDS)).thenReturn(false);

        assertThrows(SeatLockBusyException.class, () -> seatLockManager.acquire(List.of(10L)));

        verify(seat10Lock, never()).unlock();
    }

    @Test
    void allowsDatabaseFallbackWhenRedisFailsBeforeAnyLockWasAcquired() throws InterruptedException {
        when(redissonClient.getLock("ticketledger:lock:seat:10")).thenReturn(seat10Lock);
        when(seat10Lock.tryLock(0, TimeUnit.MILLISECONDS))
                .thenThrow(new RedisConnectionException("redis down"));

        SeatLockInfrastructureException result = assertThrows(
                SeatLockInfrastructureException.class,
                () -> seatLockManager.acquire(List.of(10L))
        );

        assertTrue(result.isFallbackSafe());
    }

    @Test
    void failsClosedWhenRedisFailsAfterPartialLockAcquisition() throws InterruptedException {
        when(redissonClient.getLock("ticketledger:lock:seat:10")).thenReturn(seat10Lock);
        when(redissonClient.getLock("ticketledger:lock:seat:20")).thenReturn(seat20Lock);
        when(seat10Lock.tryLock(0, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(seat20Lock.tryLock(0, TimeUnit.MILLISECONDS))
                .thenThrow(new RedisConnectionException("redis down"));
        when(seat10Lock.isHeldByCurrentThread()).thenReturn(true);

        SeatLockInfrastructureException result = assertThrows(
                SeatLockInfrastructureException.class,
                () -> seatLockManager.acquire(List.of(10L, 20L))
        );

        assertFalse(result.isFallbackSafe());
        verify(seat10Lock).unlock();
    }

    @Test
    void failsClosedWhenAWrappedRuntimeFailureOccursAfterPartialLockAcquisition() throws InterruptedException {
        when(redissonClient.getLock("ticketledger:lock:seat:10")).thenReturn(seat10Lock);
        when(redissonClient.getLock("ticketledger:lock:seat:20")).thenReturn(seat20Lock);
        when(seat10Lock.tryLock(0, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(seat20Lock.tryLock(0, TimeUnit.MILLISECONDS))
                .thenThrow(new IllegalStateException("wrapped redis failure"));
        when(seat10Lock.isHeldByCurrentThread()).thenReturn(true);

        SeatLockInfrastructureException result = assertThrows(
                SeatLockInfrastructureException.class,
                () -> seatLockManager.acquire(List.of(10L, 20L))
        );

        assertFalse(result.isFallbackSafe());
        verify(seat10Lock).unlock();
    }

    @Test
    void failsClosedWhenContentionCleanupCannotConfirmPartialUnlock() throws InterruptedException {
        when(redissonClient.getLock("ticketledger:lock:seat:10")).thenReturn(seat10Lock);
        when(redissonClient.getLock("ticketledger:lock:seat:20")).thenReturn(seat20Lock);
        when(seat10Lock.tryLock(0, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(seat20Lock.tryLock(0, TimeUnit.MILLISECONDS)).thenReturn(false);
        when(seat10Lock.isHeldByCurrentThread()).thenReturn(true);
        org.mockito.Mockito.doThrow(new RedisConnectionException("unlock response lost"))
                .when(seat10Lock).unlock();

        SeatLockInfrastructureException result = assertThrows(
                SeatLockInfrastructureException.class,
                () -> seatLockManager.acquire(List.of(10L, 20L))
        );

        assertFalse(result.isFallbackSafe());
    }
}
