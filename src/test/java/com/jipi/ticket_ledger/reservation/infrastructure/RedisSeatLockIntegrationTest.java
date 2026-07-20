package com.jipi.ticket_ledger.reservation.infrastructure;

import com.jipi.ticket_ledger.reservation.application.lock.SeatLockBusyException;
import com.jipi.ticket_ledger.reservation.application.lock.SeatLockHandle;
import com.jipi.ticket_ledger.reservation.application.lock.SeatLockInfrastructureException;
import com.jipi.ticket_ledger.reservation.application.lock.SeatLockManager;
import com.jipi.ticket_ledger.reservation.application.lock.SeatLockPolicyProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class RedisSeatLockIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    private static RedissonClient redissonClient;
    private static SeatLockManager seatLockManager;

    @BeforeAll
    static void setUp() {
        Config config = new Config();
        config.setLockWatchdogTimeout(Duration.ofSeconds(30).toMillis());
        config.useSingleServer()
                .setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379))
                .setRetryAttempts(0);
        redissonClient = Redisson.create(config);

        SeatLockPolicyProperties properties = new SeatLockPolicyProperties(
                Duration.ZERO,
                Duration.ofSeconds(30),
                2,
                Duration.ofMillis(100),
                1
        );
        seatLockManager = new RedisSeatLockManager(
                redissonClient,
                CircuitBreaker.ofDefaults("seat-lock-integration-test"),
                properties
        );
    }

    @AfterAll
    static void tearDown() {
        redissonClient.shutdown();
    }

    @Test
    void sameSeatIsRejectedImmediatelyUntilTheOwnerReleasesTheLock() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try {
            Future<?> owner = executor.submit(() -> {
                try (SeatLockHandle ignored = seatLockManager.acquire(List.of(10L))) {
                    acquired.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            });

            if (!acquired.await(15, TimeUnit.SECONDS)) {
                owner.get(1, TimeUnit.SECONDS);
            }
            assertThrows(SeatLockBusyException.class, () -> seatLockManager.acquire(List.of(10L)));

            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

            try (SeatLockHandle ignored = seatLockManager.acquire(List.of(10L))) {
                // The previous owner released the key, so the next request can acquire it.
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void redisOutageBeforeAcquisitionIsFallbackSafeAndRecoveryAllowsNewAcquisition() {
        DockerClientFactory.instance().client().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            SeatLockInfrastructureException failure = assertThrows(
                    SeatLockInfrastructureException.class,
                    () -> seatLockManager.acquire(List.of(30L))
            );
            assertTrue(failure.isFallbackSafe());
        } finally {
            DockerClientFactory.instance().client().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }

        awaitRedisAvailable();
        try (SeatLockHandle ignored = seatLockManager.acquire(List.of(30L))) {
            // Recovery keeps the lock path usable without restarting the application.
        }
    }

    private void awaitRedisAvailable() {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        RuntimeException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                redissonClient.getKeys().count();
                return;
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Redis recovery.", exception);
            }
        }
        throw new IllegalStateException("Redis did not recover within the test timeout.", lastFailure);
    }
}
