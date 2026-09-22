package com.jipi.ticket_ledger.event.application.cache;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import com.zaxxer.hikari.metrics.PoolStats;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ticketledger.cache.diagnostic", havingValue = "true")
public class CacheDiagnosticHikariConfiguration {

    @Bean
    static BeanPostProcessor cacheDiagnosticHikariPostProcessor(ObjectProvider<MeterRegistry> registryProvider) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof HikariDataSource hikariDataSource) {
                    MeterRegistry registry = registryProvider.getObject();
                    hikariDataSource.setMetricsTrackerFactory(new DiagnosticTrackerFactory(registry));
                }
                return bean;
            }
        };
    }

    private static final class DiagnosticTrackerFactory implements MetricsTrackerFactory {
        private final MetricsTrackerFactory standard;
        private final MeterRegistry registry;

        private DiagnosticTrackerFactory(MeterRegistry registry) {
            this.standard = new MicrometerMetricsTrackerFactory(registry);
            this.registry = registry;
        }

        @Override
        public IMetricsTracker create(String poolName, PoolStats poolStats) {
            IMetricsTracker delegate = standard.create(poolName, poolStats);
            return new IMetricsTracker() {
                @Override
                public void recordConnectionAcquiredNanos(long elapsedNanos) {
                    delegate.recordConnectionAcquiredNanos(elapsedNanos);
                    record("checkout", elapsedNanos, TimeUnit.NANOSECONDS);
                }

                @Override
                public void recordConnectionUsageMillis(long elapsedMillis) {
                    delegate.recordConnectionUsageMillis(elapsedMillis);
                    record("hold", elapsedMillis, TimeUnit.MILLISECONDS);
                }

                @Override
                public void recordConnectionTimeout() {
                    delegate.recordConnectionTimeout();
                    registry.counter("ticketledger.event.cache.diagnostic.jdbc.timeout").increment();
                }

                @Override
                public void recordConnectionCreatedMillis(long connectionCreatedMillis) {
                    delegate.recordConnectionCreatedMillis(connectionCreatedMillis);
                }

                @Override
                public void close() {
                    delegate.close();
                }

                private void record(String phase, long amount, TimeUnit unit) {
                    String role = CacheDiagnosticContext.role();
                    if (role == null) return;
                    registry.timer("ticketledger.event.cache.diagnostic.jdbc." + phase, "role", role)
                            .record(amount, unit);
                }
            };
        }
    }
}
