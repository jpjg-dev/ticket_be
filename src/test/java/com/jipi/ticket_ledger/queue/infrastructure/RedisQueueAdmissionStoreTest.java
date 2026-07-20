package com.jipi.ticket_ledger.queue.infrastructure;

import com.jipi.ticket_ledger.queue.application.QueueAdmissionRequiredException;
import com.jipi.ticket_ledger.queue.application.QueueAdmissionStore;
import com.jipi.ticket_ledger.queue.domain.QueueAdmissionClaimResult;
import com.jipi.ticket_ledger.queue.domain.QueueAdmissionStatus;
import com.jipi.ticket_ledger.support.RedisTestContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest(properties = "queue.admission.fixed-delay-ms=60000")
class RedisQueueAdmissionStoreTest extends RedisTestContainerSupport {

    private static final Long USER_ID = 1L;
    private static final Long SCHEDULE_ID = 10L;

    @Autowired
    private QueueAdmissionStore queueAdmissionStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private QueueAdmissionProperties properties;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clearQueueKeys() {
        awaitRedisAvailable();
        Set<String> keys = redisTemplate.keys("ticketledger:queue:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @AfterEach
    void waitForRedisAfterTest() {
        awaitRedisAvailable();
    }

    @Test
    @DisplayName("입장 토큰은 한 번만 claim할 수 있다")
    void admittedTokenCanBeClaimedOnlyOnce() {
        String token = queueAdmissionStore.register(USER_ID, SCHEDULE_ID);
        queueAdmissionStore.admitNextForActiveSchedules();

        assertEquals(QueueAdmissionClaimResult.CLAIMED,
                queueAdmissionStore.claim(USER_ID, SCHEDULE_ID, token));
        assertEquals(QueueAdmissionClaimResult.ALREADY_CLAIMED,
                queueAdmissionStore.claim(USER_ID, SCHEDULE_ID, token));
    }

    @Test
    @DisplayName("입장 허용 키가 만료되면 예약 진입을 거부한다")
    void expiredAdmissionIsRejected() {
        String token = queueAdmissionStore.register(USER_ID, SCHEDULE_ID);
        queueAdmissionStore.admitNextForActiveSchedules();
        redisTemplate.delete("ticketledger:queue:admitted:" + token);

        assertEquals(QueueAdmissionClaimResult.ADMISSION_UNAVAILABLE,
                queueAdmissionStore.claim(USER_ID, SCHEDULE_ID, token));
    }

    @Test
    @DisplayName("프로세스가 재생성되어도 Redis backlog를 이어서 승격한다")
    void newStoreInstanceContinuesExistingBacklog() {
        String token = queueAdmissionStore.register(USER_ID, SCHEDULE_ID);
        RedisQueueAdmissionStore restartedStore =
                new RedisQueueAdmissionStore(redisTemplate, properties, clock);

        assertEquals(1, restartedStore.admitNextForActiveSchedules());
        assertEquals(QueueAdmissionStatus.ADMITTED,
                restartedStore.getStatus(USER_ID, SCHEDULE_ID, token).status());
    }

    @Test
    @DisplayName("Redis 연결 장애가 복구되면 같은 대기 토큰과 순번을 이어간다")
    void redisConnectionRecoveryContinuesWaitingTokenAndPosition() {
        String firstToken = queueAdmissionStore.register(USER_ID, SCHEDULE_ID);
        String secondToken = queueAdmissionStore.register(2L, SCHEDULE_ID);
        assertEquals(1L, queueAdmissionStore.getStatus(USER_ID, SCHEDULE_ID, firstToken).position());
        assertEquals(2L, queueAdmissionStore.getStatus(2L, SCHEDULE_ID, secondToken).position());

        DockerClientFactory.instance().client().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            assertThrows(DataAccessException.class, () -> queueAdmissionStore.countWaiting(SCHEDULE_ID));
        } finally {
            DockerClientFactory.instance().client().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        awaitRedisAvailable();

        assertEquals(1L, queueAdmissionStore.getStatus(USER_ID, SCHEDULE_ID, firstToken).position());
        assertEquals(2L, queueAdmissionStore.getStatus(2L, SCHEDULE_ID, secondToken).position());
        assertEquals(2L, queueAdmissionStore.countTotalWaiting());
    }

    @Test
    @DisplayName("대기 상태가 유실되면 같은 사용자는 새 토큰으로 재등록한다")
    void lostQueueStateConvergesByReRegistration() {
        String lostToken = queueAdmissionStore.register(USER_ID, SCHEDULE_ID);
        Set<String> keys = redisTemplate.keys("ticketledger:queue:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }

        assertThrows(QueueAdmissionRequiredException.class,
                () -> queueAdmissionStore.getStatus(USER_ID, SCHEDULE_ID, lostToken));

        String recoveredToken = queueAdmissionStore.register(USER_ID, SCHEDULE_ID);

        assertNotEquals(lostToken, recoveredToken);
        assertEquals(1L, queueAdmissionStore.getStatus(USER_ID, SCHEDULE_ID, recoveredToken).position());
    }

    @Test
    @DisplayName("대기 취소는 순번과 소유권 키를 함께 제거한다")
    void cancellationRemovesWaitingEntryAtomically() {
        String token = queueAdmissionStore.register(USER_ID, SCHEDULE_ID);

        assertEquals(1L, queueAdmissionStore.countTotalWaiting());
        assertEquals(true, queueAdmissionStore.cancel(USER_ID, SCHEDULE_ID, token));
        assertEquals(0L, queueAdmissionStore.countTotalWaiting());
        assertThrows(QueueAdmissionRequiredException.class,
                () -> queueAdmissionStore.getStatus(USER_ID, SCHEDULE_ID, token));
    }

    private void awaitRedisAvailable() {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        RuntimeException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try (var connection = redisTemplate.getConnectionFactory().getConnection()) {
                if ("PONG".equals(connection.ping())) {
                    return;
                }
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                fail("Redis 복구 대기 중 interrupt가 발생했습니다.", exception);
            }
        }
        fail("Redis가 제한 시간 안에 복구되지 않았습니다.", lastFailure);
    }
}
