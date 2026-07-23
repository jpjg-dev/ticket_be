package com.jipi.ticket_ledger.reservation.infrastructure;

import com.jipi.ticket_ledger.reservation.application.lock.SeatLockFallbackGuard;
import com.jipi.ticket_ledger.reservation.application.lock.SeatLockPolicyProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(SeatLockPolicyProperties.class)
public class SeatLockConfiguration {

    @Bean(destroyMethod = "shutdown")
    @Lazy
    public RedissonClient seatLockRedissonClient(
            RedisConnectionDetails connectionDetails,
            RedisProperties redisProperties,
            SeatLockPolicyProperties properties
    ) {
        RedisConnectionDetails.Standalone standalone = connectionDetails.getStandalone();
        if (standalone == null) {
            throw new IllegalStateException("Seat lock requires a standalone Redis connection.");
        }

        Config config = new Config();
        config.setLockWatchdogTimeout(properties.watchdogTimeout().toMillis());

        String scheme = standalone.getSslBundle() == null ? "redis://" : "rediss://";
        SingleServerConfig server = config.useSingleServer()
                .setAddress(scheme + standalone.getHost() + ":" + standalone.getPort())
                .setDatabase(standalone.getDatabase())
                .setConnectTimeout(Math.toIntExact(redisProperties.getConnectTimeout().toMillis()))
                .setTimeout(Math.toIntExact(redisProperties.getTimeout().toMillis()))
                .setRetryAttempts(0);

        if (StringUtils.hasText(connectionDetails.getUsername())) {
            config.setUsername(connectionDetails.getUsername());
        }
        if (StringUtils.hasText(connectionDetails.getPassword())) {
            config.setPassword(connectionDetails.getPassword());
        }

        return Redisson.create(config);
    }

    @Bean
    public CircuitBreaker seatLockRedisCircuitBreaker(CircuitBreakerRegistry circuitBreakerRegistry) {
        return circuitBreakerRegistry.circuitBreaker("seatLockRedis");
    }

    @Bean
    public SeatLockFallbackGuard seatLockFallbackGuard(SeatLockPolicyProperties properties) {
        return new SeatLockFallbackGuard(properties);
    }
}
