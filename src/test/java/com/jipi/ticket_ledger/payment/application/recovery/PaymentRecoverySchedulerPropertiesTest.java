package com.jipi.ticket_ledger.payment.application.recovery;

import com.jipi.ticket_ledger.global.config.SchedulingConfiguration;
import com.jipi.ticket_ledger.payment.application.port.out.PaymentGatewayCircuitState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

class PaymentRecoverySchedulerPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PaymentRecoverySchedulerConfiguration.class);

    @Test
    void bindsValidMillisecondValuesIncludingZeroGracePeriod() {
        contextRunner.withPropertyValues(
                "payment.recovery-scheduler.grace-ms=0",
                "payment.recovery-scheduler.batch-size=20",
                "payment.recovery-scheduler.fixed-delay-ms=60000"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            PaymentRecoverySchedulerProperties properties = context.getBean(PaymentRecoverySchedulerProperties.class);
            assertThat(properties.getGraceMs()).isZero();
            assertThat(properties.getBatchSize()).isEqualTo(20);
            assertThat(properties.getFixedDelayMs()).isEqualTo(60_000L);
        });
    }

    @ParameterizedTest(name = "rejects missing {0} when scheduling is disabled")
    @MethodSource("missingRequiredProperties")
    void rejectsMissingRequiredConfigurationEvenWhenSchedulingIsDisabled(String missingProperty) {
        contextRunner.withPropertyValues(
                "app.scheduling.enabled=false",
                propertyValueUnlessMissing("grace-ms", "0", missingProperty),
                propertyValueUnlessMissing("batch-size", "20", missingProperty),
                propertyValueUnlessMissing("fixed-delay-ms", "60000", missingProperty)
        ).run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest(name = "rejects {1} for {0}")
    @MethodSource("invalidProperties")
    void rejectsInvalidConfigurationValues(String invalidProperty, String invalidValue) {
        contextRunner.withPropertyValues(
                propertyValueUnlessMissing("grace-ms", "0", invalidProperty),
                propertyValueUnlessMissing("batch-size", "20", invalidProperty),
                propertyValueUnlessMissing("fixed-delay-ms", "60000", invalidProperty),
                "payment.recovery-scheduler." + invalidProperty + "=" + invalidValue
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindsLegacyDockerEnvironmentNamesAheadOfProfileValues() {
        contextRunner
                .withInitializer(context -> {
                    Map<String, Object> legacyEnvironment = Map.of(
                            "PAYMENT_RECOVERY_SCHEDULER_GRACE_MS", "90000",
                            "PAYMENT_RECOVERY_SCHEDULER_FIXED_DELAY_MS", "30000"
                    );
                    context.getEnvironment().getPropertySources().addFirst(
                            new SystemEnvironmentPropertySource("legacy-systemEnvironment", legacyEnvironment)
                    );
                    context.getEnvironment().getPropertySources().addLast(new MapPropertySource(
                            "profile-recovery-scheduler-properties",
                            Map.of(
                                    "payment.recovery-scheduler.grace-ms",
                                    "60000",
                                    "payment.recovery-scheduler.fixed-delay-ms",
                                    "60000"
                            )
                    ));
                })
                .withPropertyValues("payment.recovery-scheduler.batch-size=10")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PaymentRecoverySchedulerProperties properties = context.getBean(PaymentRecoverySchedulerProperties.class);
                    assertThat(properties.getGraceMs()).isEqualTo(90_000L);
                    assertThat(properties.getFixedDelayMs()).isEqualTo(30_000L);
                    assertThat(properties.getBatchSize()).isEqualTo(10);
                });
    }

    @Test
    void registersFixedDelayFromTheNamedPropertiesBean() {
        ApplicationContextRunner schedulerContextRunner = new ApplicationContextRunner()
                .withUserConfiguration(
                        PaymentRecoverySchedulerConfiguration.class,
                        SchedulerTestConfiguration.class
                )
                .withPropertyValues(
                        "app.scheduling.enabled=true",
                        "payment.recovery-scheduler.grace-ms=0",
                        "payment.recovery-scheduler.batch-size=20",
                        "payment.recovery-scheduler.fixed-delay-ms=60000"
                );

        schedulerContextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            TaskScheduler taskScheduler = context.getBean(TaskScheduler.class);
            verify(taskScheduler).scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), eq(Duration.ofMillis(60_000L)));
        });
    }

    private static Stream<String> missingRequiredProperties() {
        return Stream.of("grace-ms", "batch-size", "fixed-delay-ms");
    }

    private static Stream<Arguments> invalidProperties() {
        return Stream.of(
                Arguments.of("grace-ms", "-1"),
                Arguments.of("batch-size", "-1"),
                Arguments.of("batch-size", "0"),
                Arguments.of("fixed-delay-ms", "-1"),
                Arguments.of("fixed-delay-ms", "0")
        );
    }

    private static String propertyValueUnlessMissing(String property, String value, String missingProperty) {
        if (property.equals(missingProperty)) {
            return "";
        }
        return "payment.recovery-scheduler." + property + "=" + value;
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SchedulingConfiguration.class, PaymentRecoveryScheduler.class})
    static class SchedulerTestConfiguration {

        @Bean
        PaymentRecoveryService paymentRecoveryService() {
            return mock(PaymentRecoveryService.class);
        }

        @Bean
        PaymentGatewayCircuitState paymentGatewayCircuitState() {
            return mock(PaymentGatewayCircuitState.class);
        }

        @Bean(name = "taskScheduler")
        TaskScheduler taskScheduler() {
            TaskScheduler taskScheduler = mock(TaskScheduler.class);
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            doReturn(future).when(taskScheduler).scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
            return taskScheduler;
        }
    }
}
