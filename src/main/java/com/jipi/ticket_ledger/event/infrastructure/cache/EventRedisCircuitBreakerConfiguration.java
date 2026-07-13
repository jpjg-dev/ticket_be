package com.jipi.ticket_ledger.event.infrastructure.cache;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EventRedisCircuitBreakerConfiguration {

    @Bean
    public CircuitBreaker eventRedisCacheCircuitBreaker(CircuitBreakerRegistry circuitBreakerRegistry) {
        return circuitBreakerRegistry.circuitBreaker("eventRedisCache");
    }
}
