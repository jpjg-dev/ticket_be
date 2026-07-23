package com.jipi.ticket_ledger.featureflag.domain;

import com.jipi.ticket_ledger.support.PostgresTestContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class QueueFeatureFlagRepositoryTest extends PostgresTestContainerSupport {

    @Autowired
    private QueueFeatureFlagRepository queueFeatureFlagRepository;

    @Test
    @DisplayName("version이 일치할 때만 queue mode를 조건부 변경한다")
    void updatesQueueModeOnlyWhenVersionMatches() {
        QueueFeatureFlag featureFlag = queueFeatureFlagRepository.findById(QueueFeatureFlag.SINGLETON_ID).orElseThrow();
        assertEquals(QueueMode.OFF, featureFlag.getQueueMode());
        assertEquals(0L, featureFlag.getVersion());

        int updatedCount = queueFeatureFlagRepository.updateModeIfVersionMatches(
                QueueFeatureFlag.SINGLETON_ID,
                QueueMode.SHADOW,
                Instant.parse("2026-07-19T00:00:00Z"),
                0L
        );

        QueueFeatureFlag reloaded = queueFeatureFlagRepository.findById(QueueFeatureFlag.SINGLETON_ID).orElseThrow();
        assertEquals(1, updatedCount);
        assertEquals(QueueMode.SHADOW, reloaded.getQueueMode());
        assertEquals(1L, reloaded.getVersion());

        int staleUpdateCount = queueFeatureFlagRepository.updateModeIfVersionMatches(
                QueueFeatureFlag.SINGLETON_ID,
                QueueMode.ENFORCED,
                Instant.parse("2026-07-19T00:01:00Z"),
                0L
        );

        assertEquals(0, staleUpdateCount);
        assertEquals(QueueMode.SHADOW,
                queueFeatureFlagRepository.findById(QueueFeatureFlag.SINGLETON_ID).orElseThrow().getQueueMode());
    }
}
