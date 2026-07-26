package com.jipi.ticket_ledger.queue.infrastructure;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueueAdmissionPropertiesTest {

    @Test
    void acceptsCapacityThatDrainsMaximumBacklogBeforeEntryTtl() {
        QueueAdmissionProperties properties = properties(
                15,
                1_000,
                Duration.ofMinutes(30),
                10_000,
                10.0,
                Duration.ofMinutes(5)
        );

        assertEquals(15.0, properties.admissionsPerSecond());
        assertEquals(Duration.ofSeconds(1_000), properties.maxBacklogDrainDuration());
    }

    @Test
    void rejectsEntryTtlThatCannotDrainMaximumBacklogWithSafetyMargin() {
        assertThrows(
                IllegalArgumentException.class,
                () -> properties(
                        20,
                        3_000,
                        Duration.ofMinutes(15),
                        10_000,
                        5.0,
                        Duration.ofMinutes(2)
                )
        );
    }

    private QueueAdmissionProperties properties(
            int batchSize,
            long fixedDelayMs,
            Duration entryTtl,
            int maxExpectedBacklog,
            double minimumAdmissionRatePerSecond,
            Duration safetyMargin
    ) {
        return new QueueAdmissionProperties(
                batchSize,
                fixedDelayMs,
                entryTtl,
                Duration.ofMinutes(2),
                Duration.ofSeconds(30),
                Duration.ofMinutes(5),
                Duration.ofSeconds(1),
                maxExpectedBacklog,
                minimumAdmissionRatePerSecond,
                safetyMargin
        );
    }
}
