package com.jipi.ticket_ledger.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jipi.ticket_ledger.event.presentation.dto.EventDetailResponse;
import com.jipi.ticket_ledger.event.presentation.dto.EventListCacheResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

@Configuration
public class CacheConfig {

    @Value("${cache.event.list.ttl}")
    private Duration eventListTtl;

    @Value("${cache.event.detail.ttl}")
    private Duration eventDetailTtl;

    @Bean
    @ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis")
    public CacheManager redisCacheManager(RedisConnectionFactory redisConnectionFactory, ObjectMapper objectMapper) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith("ticketledger:cache:")
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()));

        Map<String, RedisCacheConfiguration> cacheConfigurations = Map.of(
                CacheNames.EVENT_LIST, defaults
                        .entryTtl(eventListTtl)
                        .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                                new Jackson2JsonRedisSerializer<>(objectMapper.copy(), EventListCacheResponse.class)
                        )),
                CacheNames.EVENT_DETAIL, defaults
                        .entryTtl(eventDetailTtl)
                        .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                                new Jackson2JsonRedisSerializer<>(objectMapper.copy(), EventDetailResponse.class)
                        ))
        );

        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(cacheConfigurations)
                .transactionAware()
                .enableStatistics()
                .build();
    }
}
