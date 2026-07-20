package com.jipi.ticket_ledger.queue.infrastructure;

import com.jipi.ticket_ledger.queue.application.QueueAdmissionRequiredException;
import com.jipi.ticket_ledger.queue.application.QueueAdmissionStore;
import com.jipi.ticket_ledger.queue.domain.QueueAdmissionClaimResult;
import com.jipi.ticket_ledger.queue.domain.QueueAdmissionStatus;
import com.jipi.ticket_ledger.support.RedisTestContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
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
        Set<String> keys = redisTemplate.keys("ticketledger:queue:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
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
    @DisplayName("대기 취소는 순번과 소유권 키를 함께 제거한다")
    void cancellationRemovesWaitingEntryAtomically() {
        String token = queueAdmissionStore.register(USER_ID, SCHEDULE_ID);

        assertEquals(1L, queueAdmissionStore.countTotalWaiting());
        assertEquals(true, queueAdmissionStore.cancel(USER_ID, SCHEDULE_ID, token));
        assertEquals(0L, queueAdmissionStore.countTotalWaiting());
        assertThrows(QueueAdmissionRequiredException.class,
                () -> queueAdmissionStore.getStatus(USER_ID, SCHEDULE_ID, token));
    }
}
