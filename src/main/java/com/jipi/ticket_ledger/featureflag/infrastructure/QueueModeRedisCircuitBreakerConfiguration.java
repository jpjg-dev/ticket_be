package com.jipi.ticket_ledger.featureflag.infrastructure;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(QueueModeCacheProperties.class)
public class QueueModeRedisCircuitBreakerConfiguration {

    @Bean
    public CircuitBreaker queueModeRedisCacheReadCircuitBreaker(CircuitBreakerRegistry circuitBreakerRegistry) {
        return circuitBreakerRegistry.circuitBreaker("queueModeRedisCacheRead");
    }

    @Bean
    public CircuitBreaker queueModeRedisCacheWriteCircuitBreaker(CircuitBreakerRegistry circuitBreakerRegistry) {
        return circuitBreakerRegistry.circuitBreaker("queueModeRedisCacheWrite");
    }
}
