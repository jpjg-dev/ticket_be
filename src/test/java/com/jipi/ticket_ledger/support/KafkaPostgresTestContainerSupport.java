package com.jipi.ticket_ledger.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;

public abstract class KafkaPostgresTestContainerSupport extends PostgresTestContainerSupport {

    @Container
    @ServiceConnection
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.2");
}
