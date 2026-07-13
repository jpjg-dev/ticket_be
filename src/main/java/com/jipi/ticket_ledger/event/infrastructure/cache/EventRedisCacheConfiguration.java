package com.jipi.ticket_ledger.event.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jipi.ticket_ledger.event.application.cache.EventCachePolicyProperties;
import com.jipi.ticket_ledger.event.application.model.EventDetailResponse;
import com.jipi.ticket_ledger.event.application.model.EventListCacheResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(EventCachePolicyProperties.class)
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
public class EventRedisCacheConfiguration {

    @Bean
    public EventCacheTtlPolicy eventCacheTtlPolicy(
            Clock clock,
            EventCachePolicyProperties policy,
            @Value("${cache.event.list.ttl}") Duration eventListTtl,
            @Value("${cache.event.detail.ttl}") Duration eventDetailTtl
    ) {
        return new EventCacheTtlPolicy(clock, policy, eventListTtl, eventDetailTtl);
    }

    @Bean
    public CacheManager redisCacheManager(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper,
                                          EventCacheTtlPolicy ttlPolicy) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith("ticketledger:cache:")
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()));

        Map<String, RedisCacheConfiguration> cacheConfigurations = Map.of(
                EventCacheNames.EVENT_LIST, defaults
                        .entryTtl(ttlPolicy)
                        .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                                new Jackson2JsonRedisSerializer<>(objectMapper.copy(), EventListCacheResponse.class)
                        )),
                EventCacheNames.EVENT_DETAIL, defaults
                        .entryTtl(ttlPolicy)
                        .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                                new Jackson2JsonRedisSerializer<>(objectMapper.copy(), EventDetailResponse.class)
                        ))
        );

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(cacheConfigurations)
                .transactionAware()
                .enableStatistics()
                .build();
    }
}
