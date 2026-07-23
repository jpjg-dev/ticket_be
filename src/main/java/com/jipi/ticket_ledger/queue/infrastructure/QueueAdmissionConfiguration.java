package com.jipi.ticket_ledger.queue.infrastructure;

import com.jipi.ticket_ledger.queue.application.QueueAutoActivationPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties({QueueAdmissionProperties.class, QueueAutoActivationProperties.class})
public class QueueAdmissionConfiguration {

    @Bean(name = "queueAutoActivationTaskScheduler", defaultCandidate = false)
    public ThreadPoolTaskScheduler queueAutoActivationTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("queue-auto-activation-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }

    @Bean
    public QueueAutoActivationPolicy queueAutoActivationPolicy(QueueAutoActivationProperties properties) {
        return new QueueAutoActivationPolicy(
                properties.enabled(),
                toPolicyThreshold(properties.activate()),
                toPolicyThreshold(properties.deactivate()),
                properties.activationSamples(),
                properties.deactivationSamples(),
                properties.minimumEnforcedDuration(),
                properties.cooldown()
        );
    }

    @Bean
    public ExecutorService queueStatusExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    private QueueAutoActivationPolicy.LoadThreshold toPolicyThreshold(
            QueueAutoActivationProperties.LoadThreshold threshold
    ) {
        return new QueueAutoActivationPolicy.LoadThreshold(
                threshold.requestRate(),
                threshold.concurrentRequests(),
                threshold.processCpu(),
                threshold.tomcatBusyRatio(),
                threshold.hikariPending()
        );
    }
}
