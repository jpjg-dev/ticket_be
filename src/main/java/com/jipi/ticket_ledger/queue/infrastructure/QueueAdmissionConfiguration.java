package com.jipi.ticket_ledger.queue.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties(QueueAdmissionProperties.class)
public class QueueAdmissionConfiguration {

    @Bean
    public ExecutorService queueStatusExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
