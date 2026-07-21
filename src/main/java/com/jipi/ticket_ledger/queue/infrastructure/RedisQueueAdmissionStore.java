package com.jipi.ticket_ledger.queue.infrastructure;

import com.jipi.ticket_ledger.queue.application.QueueAdmissionRequiredException;
import com.jipi.ticket_ledger.queue.application.QueueAdmissionStore;
import com.jipi.ticket_ledger.queue.domain.QueueAdmissionSnapshot;
import com.jipi.ticket_ledger.queue.domain.QueueAdmissionClaimResult;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RedisQueueAdmissionStore implements QueueAdmissionStore {

    private static final String PREFIX = "ticketledger:queue:";
    private static final String ACTIVE_SCHEDULES_KEY = PREFIX + "active-schedules";

    private static final DefaultRedisScript<String> REGISTER_SCRIPT = new DefaultRedisScript<>("""
            local existing = redis.call('GET', KEYS[1])
            if existing then
                return existing
            end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[5])
            redis.call('SET', KEYS[2], ARGV[2] .. ':' .. ARGV[3], 'PX', ARGV[5])
            redis.call('ZADD', KEYS[3], 'NX', ARGV[4], ARGV[1])
            redis.call('SADD', KEYS[4], ARGV[3])
            return ARGV[1]
            """, String.class);

    private static final DefaultRedisScript<Long> PROMOTE_SCRIPT = new DefaultRedisScript<>("""
            local tokens = redis.call('ZRANGE', KEYS[1], 0, tonumber(ARGV[1]) - 1)
            local promoted = 0
            for _, token in ipairs(tokens) do
                local owner = redis.call('GET', ARGV[2] .. token)
                redis.call('ZREM', KEYS[1], token)
                if owner then
                    redis.call('SET', ARGV[3] .. token, owner, 'PX', ARGV[4])
                    promoted = promoted + 1
                end
            end
            if redis.call('ZCARD', KEYS[1]) == 0 then
                redis.call('SREM', KEYS[2], ARGV[5])
            end
            return promoted
            """, Long.class);

    private static final DefaultRedisScript<String> GET_STATUS_SCRIPT = new DefaultRedisScript<>("""
            local owner = redis.call('GET', KEYS[1])
            if owner ~= ARGV[1] then
                return 'INVALID'
            end
            if redis.call('GET', KEYS[2]) == ARGV[1]
                    or redis.call('GET', KEYS[3]) == ARGV[1] then
                return 'ADMITTED'
            end
            local rank = redis.call('ZRANK', KEYS[4], ARGV[2])
            if rank then
                return 'WAITING:' .. rank
            end
            if redis.call('GET', KEYS[5]) == ARGV[2] then
                redis.call('DEL', KEYS[5])
            end
            redis.call('DEL', KEYS[1], KEYS[2], KEYS[3])
            return 'INVALID'
            """, String.class);

    private static final DefaultRedisScript<Long> CLAIM_SCRIPT = new DefaultRedisScript<>("""
            local owner = redis.call('GET', KEYS[1])
            if not owner or owner ~= ARGV[1] then
                return 0
            end
            if redis.call('GET', KEYS[3]) == ARGV[1] then
                return 3
            end
            local admitted = redis.call('GET', KEYS[2])
            if admitted ~= ARGV[1] then
                return 2
            end
            if not redis.call('SET', KEYS[3], ARGV[1], 'NX', 'PX', ARGV[2]) then
                return 3
            end
            redis.call('DEL', KEYS[2])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            local claimed = redis.call('GET', KEYS[1])
            if claimed ~= ARGV[1] then
                return 0
            end
            redis.call('DEL', KEYS[1])
            redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[2])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> COMPLETE_SCRIPT = new DefaultRedisScript<>("""
            local claimed = redis.call('GET', KEYS[1])
            if claimed ~= ARGV[1] then
                return 0
            end
            redis.call('DEL', KEYS[1], KEYS[2], KEYS[3], KEYS[4])
            redis.call('ZREM', KEYS[5], ARGV[2])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> CANCEL_SCRIPT = new DefaultRedisScript<>("""
            local owner = redis.call('GET', KEYS[1])
            if owner ~= ARGV[1] or redis.call('EXISTS', KEYS[2]) == 1 then
                return 0
            end
            redis.call('ZREM', KEYS[3], ARGV[2])
            redis.call('DEL', KEYS[1], KEYS[4], KEYS[5])
            if redis.call('ZCARD', KEYS[3]) == 0 then
                redis.call('SREM', KEYS[6], ARGV[3])
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final QueueAdmissionProperties properties;
    private final Clock clock;

    @Override
    public String register(Long userId, Long scheduleId) {
        String token = UUID.randomUUID().toString();
        return redisTemplate.execute(
                REGISTER_SCRIPT,
                List.of(userEntryKey(userId, scheduleId), ownerKey(token), waitingKey(scheduleId), ACTIVE_SCHEDULES_KEY),
                token,
                userId.toString(),
                scheduleId.toString(),
                Long.toString(clock.millis()),
                Long.toString(properties.entryTtl().toMillis())
        );
    }

    @Override
    public QueueAdmissionSnapshot getStatus(Long userId, Long scheduleId, String queueToken) {
        String expectedOwner = ownerValue(userId, scheduleId);
        String result = redisTemplate.execute(
                GET_STATUS_SCRIPT,
                List.of(
                        ownerKey(queueToken),
                        admittedKey(queueToken),
                        claimedKey(queueToken),
                        waitingKey(scheduleId),
                        userEntryKey(userId, scheduleId)
                ),
                expectedOwner,
                queueToken
        );
        if ("ADMITTED".equals(result)) {
            return QueueAdmissionSnapshot.admitted(queueToken);
        }
        if (result != null && result.startsWith("WAITING:")) {
            long rank = Long.parseLong(result.substring("WAITING:".length()));
            long position = rank + 1;
            long rounds = (position + properties.batchSize() - 1) / properties.batchSize();
            long admissionIntervalSeconds = Math.max(1, (properties.fixedDelayMs() + 999) / 1000);
            long estimatedSeconds = rounds * admissionIntervalSeconds;
            return QueueAdmissionSnapshot.waiting(queueToken, position, estimatedSeconds);
        }
        throw new QueueAdmissionRequiredException();
    }

    @Override
    public QueueAdmissionClaimResult claim(Long userId, Long scheduleId, String queueToken) {
        Long result = redisTemplate.execute(
                CLAIM_SCRIPT,
                List.of(ownerKey(queueToken), admittedKey(queueToken), claimedKey(queueToken)),
                ownerValue(userId, scheduleId),
                Long.toString(properties.claimTtl().toMillis())
        );
        if (Long.valueOf(1L).equals(result)) {
            return QueueAdmissionClaimResult.CLAIMED;
        }
        if (Long.valueOf(2L).equals(result)) {
            return QueueAdmissionClaimResult.ADMISSION_UNAVAILABLE;
        }
        if (Long.valueOf(3L).equals(result)) {
            return QueueAdmissionClaimResult.ALREADY_CLAIMED;
        }
        return QueueAdmissionClaimResult.INVALID_OWNER;
    }

    @Override
    public void release(Long userId, Long scheduleId, String queueToken) {
        redisTemplate.execute(
                RELEASE_SCRIPT,
                List.of(claimedKey(queueToken), admittedKey(queueToken)),
                ownerValue(userId, scheduleId),
                Long.toString(properties.admissionTtl().toMillis())
        );
    }

    @Override
    public void complete(Long userId, Long scheduleId, String queueToken) {
        redisTemplate.execute(
                COMPLETE_SCRIPT,
                List.of(
                        claimedKey(queueToken),
                        admittedKey(queueToken),
                        ownerKey(queueToken),
                        userEntryKey(userId, scheduleId),
                        waitingKey(scheduleId)
                ),
                ownerValue(userId, scheduleId),
                queueToken
        );
    }

    @Override
    public boolean cancel(Long userId, Long scheduleId, String queueToken) {
        Long result = redisTemplate.execute(
                CANCEL_SCRIPT,
                List.of(
                        ownerKey(queueToken),
                        claimedKey(queueToken),
                        waitingKey(scheduleId),
                        userEntryKey(userId, scheduleId),
                        admittedKey(queueToken),
                        ACTIVE_SCHEDULES_KEY
                ),
                ownerValue(userId, scheduleId),
                queueToken,
                scheduleId.toString()
        );
        return Long.valueOf(1L).equals(result);
    }

    @Override
    public int admitNextForActiveSchedules() {
        Set<String> scheduleIds = redisTemplate.opsForSet().members(ACTIVE_SCHEDULES_KEY);
        if (scheduleIds == null || scheduleIds.isEmpty()) {
            return 0;
        }

        int admitted = 0;
        for (String scheduleId : scheduleIds) {
            Long result = redisTemplate.execute(
                    PROMOTE_SCRIPT,
                    List.of(waitingKey(Long.valueOf(scheduleId)), ACTIVE_SCHEDULES_KEY),
                    Integer.toString(properties.batchSize()),
                    PREFIX + "owner:",
                    PREFIX + "admitted:",
                    Long.toString(properties.admissionTtl().toMillis()),
                    scheduleId
            );
            admitted += result == null ? 0 : result.intValue();
        }
        return admitted;
    }

    @Override
    public long countWaiting(Long scheduleId) {
        Long count = redisTemplate.opsForZSet().size(waitingKey(scheduleId));
        return count == null ? 0L : count;
    }

    @Override
    public long countTotalWaiting() {
        Set<String> scheduleIds = redisTemplate.opsForSet().members(ACTIVE_SCHEDULES_KEY);
        if (scheduleIds == null || scheduleIds.isEmpty()) {
            return 0L;
        }
        return scheduleIds.stream()
                .map(Long::valueOf)
                .mapToLong(this::countWaiting)
                .sum();
    }

    private String waitingKey(Long scheduleId) {
        return PREFIX + "waiting:" + scheduleId;
    }

    private String userEntryKey(Long userId, Long scheduleId) {
        return PREFIX + "entry:" + scheduleId + ":" + userId;
    }

    private String ownerKey(String queueToken) {
        return PREFIX + "owner:" + queueToken;
    }

    private String admittedKey(String queueToken) {
        return PREFIX + "admitted:" + queueToken;
    }

    private String claimedKey(String queueToken) {
        return PREFIX + "claimed:" + queueToken;
    }

    private String ownerValue(Long userId, Long scheduleId) {
        return userId + ":" + scheduleId;
    }
}
