package com.jipi.ticket_ledger.global.config;

import com.jipi.ticket_ledger.payment.application.outbox.PaymentOutboxRelayScheduler;
import com.jipi.ticket_ledger.payment.application.outbox.PaymentOutboxRelayService;
import com.jipi.ticket_ledger.paymentaudit.application.maintenance.PaymentEventMaintenanceScheduler;
import com.jipi.ticket_ledger.paymentaudit.application.maintenance.PaymentEventMaintenanceService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PaymentEventSchedulerActivationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulerTestConfiguration.class)
            .withPropertyValues(
                    "payment.outbox.relay.enabled=true",
                    "payment.outbox.relay.fixed-delay=1h",
                    "payment.event-maintenance.enabled=true",
                    "payment.event-maintenance.fixed-delay=1h"
            );

    @Test
    void featureSchedulersAreCreatedWhenGlobalSchedulingUsesItsDefaultEnabledValue() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PaymentOutboxRelayScheduler.class);
            assertThat(context).hasSingleBean(PaymentEventMaintenanceScheduler.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            SchedulingConfiguration.class,
            PaymentOutboxRelayScheduler.class,
            PaymentEventMaintenanceScheduler.class
    })
    static class SchedulerTestConfiguration {

        @Bean
        PaymentOutboxRelayService paymentOutboxRelayService() {
            return mock(PaymentOutboxRelayService.class);
        }

        @Bean
        PaymentEventMaintenanceService paymentEventMaintenanceService() {
            return mock(PaymentEventMaintenanceService.class);
        }

        @Bean(name = "paymentOutboxTaskScheduler")
        ThreadPoolTaskScheduler paymentOutboxTaskScheduler() {
            return taskScheduler("test-payment-outbox-");
        }

        @Bean(name = "paymentEventMaintenanceTaskScheduler")
        ThreadPoolTaskScheduler paymentEventMaintenanceTaskScheduler() {
            return taskScheduler("test-payment-maintenance-");
        }

        private ThreadPoolTaskScheduler taskScheduler(String threadNamePrefix) {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix(threadNamePrefix);
            return scheduler;
        }
    }
}
