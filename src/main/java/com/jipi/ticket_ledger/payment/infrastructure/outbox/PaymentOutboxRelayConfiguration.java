package com.jipi.ticket_ledger.payment.infrastructure.outbox;

import com.jipi.ticket_ledger.payment.application.outbox.PaymentOutboxRelayProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableConfigurationProperties(PaymentOutboxRelayProperties.class)
public class PaymentOutboxRelayConfiguration {

    @Bean(name = "paymentOutboxTaskScheduler")
    @ConditionalOnProperty(name = "payment.outbox.relay.enabled", havingValue = "true")
    public ThreadPoolTaskScheduler paymentOutboxTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("payment-outbox-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
