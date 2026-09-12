package com.jipi.ticket_ledger.payment.application.recovery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties
public class PaymentRecoverySchedulerConfiguration {

    @Bean(name = "paymentRecoverySchedulerProperties")
    @ConfigurationProperties(prefix = "payment.recovery-scheduler")
    @Validated
    PaymentRecoverySchedulerProperties paymentRecoverySchedulerProperties() {
        return new PaymentRecoverySchedulerProperties();
    }
}
