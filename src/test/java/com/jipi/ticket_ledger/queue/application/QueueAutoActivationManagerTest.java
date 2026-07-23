package com.jipi.ticket_ledger.queue.application;

import com.jipi.ticket_ledger.featureflag.domain.QueueMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueueAutoActivationManagerTest {

    private MutableClock clock;
    private QueueAutoActivationManager manager;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-07-23T00:00:00Z"));
        QueueAutoActivationPolicy policy = new QueueAutoActivationPolicy(
                true,
                new QueueAutoActivationPolicy.LoadThreshold(10.0, 5, 0.70, 0.70, 1),
                new QueueAutoActivationPolicy.LoadThreshold(2.0, 1, 0.40, 0.30, 0),
                2,
                2,
                Duration.ofSeconds(10),
                Duration.ofSeconds(5)
        );
        manager = new QueueAutoActivationManager(policy, clock, new SimpleMeterRegistry());
        manager.evaluate(QueueMode.OFF, snapshot(0, 0, 0, 0, 0, 0));
        clock.advance(Duration.ofSeconds(5));
    }

    @Test
    void startsEnforcedUntilTelemetryProvesRecovery() {
        QueueAutoActivationPolicy policy = new QueueAutoActivationPolicy(
                true,
                new QueueAutoActivationPolicy.LoadThreshold(10.0, 5, 0.70, 0.70, 1),
                new QueueAutoActivationPolicy.LoadThreshold(2.0, 1, 0.40, 0.30, 0),
                2,
                2,
                Duration.ofSeconds(10),
                Duration.ofSeconds(5)
        );
        QueueAutoActivationManager startupManager =
                new QueueAutoActivationManager(policy, clock, new SimpleMeterRegistry());

        assertTrue(startupManager.isAutoEnforced());
        assertEquals(QueueMode.ENFORCED, startupManager.resolve(QueueMode.SHADOW));
    }

    @Test
    void activatesOnlyAfterConsecutiveOverloadedSamples() {
        QueueLoadSnapshot overloaded = snapshot(11.0, 1, 0.30, 0.20, 0, 0);

        manager.evaluate(QueueMode.SHADOW, overloaded);
        assertFalse(manager.isAutoEnforced());

        clock.advance(Duration.ofSeconds(1));
        manager.evaluate(QueueMode.SHADOW, overloaded);

        assertTrue(manager.isAutoEnforced());
        assertEquals(QueueMode.ENFORCED, manager.resolve(QueueMode.SHADOW));
    }

    @Test
    void doesNotActivateWhenOverloadSamplesAreInterrupted() {
        QueueLoadSnapshot overloaded = snapshot(11.0, 1, 0.30, 0.20, 0, 0);
        QueueLoadSnapshot normal = snapshot(1.0, 0, 0.20, 0.10, 0, 0);

        manager.evaluate(QueueMode.SHADOW, overloaded);
        clock.advance(Duration.ofSeconds(1));
        manager.evaluate(QueueMode.SHADOW, normal);
        clock.advance(Duration.ofSeconds(1));
        manager.evaluate(QueueMode.SHADOW, overloaded);

        assertFalse(manager.isAutoEnforced());
    }

    @Test
    void activatesWhenTelemetryRemainsIncomplete() {
        QueueLoadSnapshot incomplete = new QueueLoadSnapshot(
                0.0,
                0,
                Double.NaN,
                Double.NaN,
                0,
                0,
                false
        );

        manager.evaluate(QueueMode.SHADOW, incomplete);
        clock.advance(Duration.ofSeconds(1));
        manager.evaluate(QueueMode.SHADOW, incomplete);

        assertTrue(manager.isAutoEnforced());
    }

    @Test
    void keepsQueueUntilMinimumDurationPassesAndBacklogDrains() {
        QueueLoadSnapshot overloaded = snapshot(11.0, 1, 0.30, 0.20, 0, 0);
        manager.evaluate(QueueMode.SHADOW, overloaded);
        clock.advance(Duration.ofSeconds(1));
        manager.evaluate(QueueMode.SHADOW, overloaded);

        QueueLoadSnapshot recoveredWithBacklog = snapshot(1.0, 0, 0.20, 0.10, 0, 3);
        clock.advance(Duration.ofSeconds(10));
        manager.evaluate(QueueMode.SHADOW, recoveredWithBacklog);
        manager.evaluate(QueueMode.SHADOW, recoveredWithBacklog);
        assertTrue(manager.isAutoEnforced());

        QueueLoadSnapshot recovered = snapshot(1.0, 0, 0.20, 0.10, 0, 0);
        manager.evaluate(QueueMode.SHADOW, recovered);
        clock.advance(Duration.ofSeconds(1));
        manager.evaluate(QueueMode.SHADOW, recovered);

        assertFalse(manager.isAutoEnforced());
        assertEquals(QueueMode.SHADOW, manager.resolve(QueueMode.SHADOW));
    }

    @Test
    void enforcedModePreservesAutomaticStateButOffResetsIt() {
        QueueLoadSnapshot overloaded = snapshot(11.0, 1, 0.30, 0.20, 0, 0);
        manager.evaluate(QueueMode.SHADOW, overloaded);
        clock.advance(Duration.ofSeconds(1));
        manager.evaluate(QueueMode.SHADOW, overloaded);
        assertTrue(manager.isAutoEnforced());

        assertEquals(QueueMode.OFF, manager.resolve(QueueMode.OFF));
        assertEquals(QueueMode.ENFORCED, manager.resolve(QueueMode.ENFORCED));

        manager.evaluate(QueueMode.ENFORCED, overloaded);
        assertTrue(manager.isAutoEnforced());

        manager.evaluate(QueueMode.OFF, overloaded);
        assertFalse(manager.isAutoEnforced());
    }

    @Test
    void doesNotDeactivateWhenTelemetryIsIncomplete() {
        QueueLoadSnapshot overloaded = snapshot(11.0, 1, 0.30, 0.20, 0, 0);
        manager.evaluate(QueueMode.SHADOW, overloaded);
        clock.advance(Duration.ofSeconds(1));
        manager.evaluate(QueueMode.SHADOW, overloaded);
        assertTrue(manager.isAutoEnforced());

        clock.advance(Duration.ofSeconds(10));
        QueueLoadSnapshot incomplete = new QueueLoadSnapshot(
                1.0,
                0,
                Double.NaN,
                0.10,
                0,
                0,
                false
        );
        manager.evaluate(QueueMode.SHADOW, incomplete);
        manager.evaluate(QueueMode.SHADOW, incomplete);

        assertTrue(manager.isAutoEnforced());
    }

    private QueueLoadSnapshot snapshot(
            double requestRate,
            int concurrentRequests,
            double processCpu,
            double tomcatBusyRatio,
            int hikariPending,
            long waitingUsers
    ) {
        return new QueueLoadSnapshot(
                requestRate,
                concurrentRequests,
                processCpu,
                tomcatBusyRatio,
                hikariPending,
                waitingUsers,
                true
        );
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
