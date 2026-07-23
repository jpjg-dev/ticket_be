package com.jipi.ticket_ledger.queue.infrastructure;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;

class QueueAutoActivationPropertiesTest {

    @Test
    void rejectsDeactivationThresholdAboveActivationThreshold() {
        QueueAutoActivationProperties.LoadThreshold activate =
                new QueueAutoActivationProperties.LoadThreshold(10, 5, 0.7, 0.7, 1);
        QueueAutoActivationProperties.LoadThreshold deactivate =
                new QueueAutoActivationProperties.LoadThreshold(11, 1, 0.4, 0.4, 0);

        assertThrows(IllegalArgumentException.class, () -> new QueueAutoActivationProperties(
                true,
                1_000,
                activate,
                deactivate,
                3,
                10,
                Duration.ofSeconds(30),
                Duration.ofSeconds(30)
        ));
    }
}
