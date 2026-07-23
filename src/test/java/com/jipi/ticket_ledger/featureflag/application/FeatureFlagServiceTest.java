package com.jipi.ticket_ledger.featureflag.application;

import com.jipi.ticket_ledger.featureflag.domain.QueueFeatureFlag;
import com.jipi.ticket_ledger.featureflag.domain.QueueFeatureFlagRepository;
import com.jipi.ticket_ledger.featureflag.domain.QueueMode;
import com.jipi.ticket_ledger.featureflag.domain.QueueModeSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeatureFlagServiceTest {

    @Mock
    private QueueFeatureFlagRepository queueFeatureFlagRepository;

    @Mock
    private QueueModeCache queueModeCache;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private FeatureFlagService featureFlagService;

    @BeforeEach
    void setUp() {
        featureFlagService = new FeatureFlagService(
                queueFeatureFlagRepository,
                queueModeCache,
                eventPublisher,
                Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    @DisplayName("Redis 읽기 실패 시 런타임 큐 모드는 DB를 조회하지 않고 ENFORCED로 닫힌다")
    void runtimeModeFailsClosedWhenRedisReadFails() {
        when(queueModeCache.findQueueMode()).thenThrow(new QueueModeCacheAccessException(new IllegalStateException("redis down")));

        assertEquals(QueueMode.ENFORCED, featureFlagService.getQueueModeForRuntime());

        verify(queueFeatureFlagRepository, never()).findById(QueueFeatureFlag.SINGLETON_ID);
    }

    @Test
    @DisplayName("Redis cache miss면 PostgreSQL 값을 읽고 cache를 채운다")
    void runtimeModeLoadsDurableValueOnCacheMiss() {
        QueueFeatureFlag featureFlag = new QueueFeatureFlag(QueueMode.SHADOW, Instant.parse("2026-07-19T00:00:00Z"));
        when(queueModeCache.findQueueMode()).thenReturn(Optional.empty());
        when(queueFeatureFlagRepository.findById(QueueFeatureFlag.SINGLETON_ID)).thenReturn(Optional.of(featureFlag));
        when(queueModeCache.putIfNewer(new QueueModeSnapshot(QueueMode.SHADOW, 0L))).thenReturn(true);

        assertEquals(QueueMode.SHADOW, featureFlagService.getQueueModeForRuntime());

        verify(queueModeCache).putIfNewer(new QueueModeSnapshot(QueueMode.SHADOW, 0L));
    }

    @Test
    @DisplayName("cache miss 중 더 최신 mode가 저장되면 오래된 DB snapshot 대신 최신 cache를 사용한다")
    void runtimeModeUsesNewerCacheWhenWarmupLosesVersionRace() {
        QueueFeatureFlag staleDatabaseFlag = new QueueFeatureFlag(
                QueueMode.SHADOW,
                Instant.parse("2026-07-19T00:00:00Z")
        );
        QueueModeSnapshot newerCacheSnapshot = new QueueModeSnapshot(QueueMode.ENFORCED, 1L);
        when(queueModeCache.findQueueMode())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(newerCacheSnapshot));
        when(queueFeatureFlagRepository.findById(QueueFeatureFlag.SINGLETON_ID))
                .thenReturn(Optional.of(staleDatabaseFlag));
        when(queueModeCache.putIfNewer(new QueueModeSnapshot(QueueMode.SHADOW, 0L))).thenReturn(false);

        assertEquals(QueueMode.ENFORCED, featureFlagService.getQueueModeForRuntime());
    }

    @Test
    @DisplayName("관리자 변경은 version 조건부 update 후 cache refresh event를 발행한다")
    void updateQueueModeUsesConditionalUpdateAndPublishesRefreshEvent() {
        QueueFeatureFlag updatedFeatureFlag = new QueueFeatureFlag(
                QueueMode.ENFORCED,
                Instant.parse("2026-07-19T00:00:00Z")
        );
        org.springframework.test.util.ReflectionTestUtils.setField(updatedFeatureFlag, "version", 1L);
        when(queueFeatureFlagRepository.updateModeIfVersionMatches(
                QueueFeatureFlag.SINGLETON_ID,
                QueueMode.ENFORCED,
                Instant.parse("2026-07-19T00:00:00Z"),
                0L
        )).thenReturn(1);
        when(queueFeatureFlagRepository.findById(QueueFeatureFlag.SINGLETON_ID))
                .thenReturn(Optional.of(updatedFeatureFlag));

        QueueModeSnapshot result = featureFlagService.updateQueueMode(QueueMode.ENFORCED, 0L);

        assertEquals(new QueueModeSnapshot(QueueMode.ENFORCED, 1L), result);
        verify(eventPublisher).publishEvent(new QueueModeChangedEvent(result));
    }

    @Test
    @DisplayName("오래된 관리자 버전으로 변경하면 충돌 예외를 발생시킨다")
    void rejectsStaleAdminVersion() {
        when(queueFeatureFlagRepository.updateModeIfVersionMatches(
                QueueFeatureFlag.SINGLETON_ID,
                QueueMode.ENFORCED,
                Instant.parse("2026-07-19T00:00:00Z"),
                1L
        )).thenReturn(0);

        org.junit.jupiter.api.Assertions.assertThrows(FeatureFlagVersionConflictException.class,
                () -> featureFlagService.updateQueueMode(QueueMode.ENFORCED, 1L));
        verify(queueFeatureFlagRepository, never()).findById(QueueFeatureFlag.SINGLETON_ID);
    }
}
