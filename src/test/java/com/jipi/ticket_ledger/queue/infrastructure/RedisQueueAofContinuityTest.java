package com.jipi.ticket_ledger.queue.infrastructure;

import com.jipi.ticket_ledger.queue.domain.QueueAdmissionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisQueueAofContinuityTest {

    private static final Long USER_ID = 1L;
    private static final Long SCHEDULE_ID = 10L;
    private static final QueueAdmissionProperties PROPERTIES = new QueueAdmissionProperties(
            20,
            60000,
            Duration.ofMinutes(15),
            Duration.ofMinutes(2),
            Duration.ofSeconds(30),
            Duration.ofMinutes(5),
            Duration.ofSeconds(1)
    );

    @TempDir
    Path redisDataDirectory;

    @Test
    void restartsWithSameWaitingTokenFromAof() throws InterruptedException {
        String queueToken;
        try (GenericContainer<?> firstRedis = createRedisContainer()) {
            firstRedis.start();
            try (RedisClient client = connect(firstRedis)) {
                RedisQueueAdmissionStore store = client.createStore();
                queueToken = store.register(USER_ID, SCHEDULE_ID);
                assertEquals(1L, store.getStatus(USER_ID, SCHEDULE_ID, queueToken).position());
                Thread.sleep(Duration.ofSeconds(2));
            }
        }

        try (GenericContainer<?> recoveredRedis = createRedisContainer()) {
            recoveredRedis.start();
            try (RedisClient client = connect(recoveredRedis)) {
                RedisQueueAdmissionStore recoveredStore = client.createStore();

                assertEquals(QueueAdmissionStatus.WAITING,
                        recoveredStore.getStatus(USER_ID, SCHEDULE_ID, queueToken).status());
                assertEquals(1L,
                        recoveredStore.getStatus(USER_ID, SCHEDULE_ID, queueToken).position());
                assertEquals(1L, recoveredStore.countTotalWaiting());
            }
        }
    }

    private GenericContainer<?> createRedisContainer() {
        return new GenericContainer<>("redis:7.4-alpine")
                .withExposedPorts(6379)
                .withFileSystemBind(redisDataDirectory.toString(), "/data", BindMode.READ_WRITE)
                .withCommand("redis-server", "--appendonly", "yes", "--appendfsync", "everysec")
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));
    }

    private RedisClient connect(GenericContainer<?> redis) {
        LettuceConnectionFactory connectionFactory =
                new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        return new RedisClient(connectionFactory, redisTemplate);
    }

    private record RedisClient(
            LettuceConnectionFactory connectionFactory,
            StringRedisTemplate redisTemplate
    ) implements AutoCloseable {

        RedisQueueAdmissionStore createStore() {
            return new RedisQueueAdmissionStore(redisTemplate, PROPERTIES, Clock.systemUTC());
        }

        @Override
        public void close() {
            connectionFactory.destroy();
        }
    }
}
