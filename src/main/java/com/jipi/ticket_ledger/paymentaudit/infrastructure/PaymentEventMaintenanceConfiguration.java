package com.jipi.ticket_ledger.paymentaudit.infrastructure;

import com.jipi.ticket_ledger.paymentaudit.application.maintenance.PaymentEventMaintenanceProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableConfigurationProperties(PaymentEventMaintenanceProperties.class)
public class PaymentEventMaintenanceConfiguration {

    @Bean(name = "paymentEventMaintenanceTaskScheduler")
    @ConditionalOnProperty(name = "payment.event-maintenance.enabled", havingValue = "true")
    public ThreadPoolTaskScheduler paymentEventMaintenanceTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("payment-event-maintenance-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
