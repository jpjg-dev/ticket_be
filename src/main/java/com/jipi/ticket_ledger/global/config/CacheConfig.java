package com.jipi.ticket_ledger.global.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class CacheConfig {

    @Value("${cache.event.list.ttl}")
    private Duration eventListTtl;

    @Value("${cache.event.list.max-size}")
    private long eventListMaxSize;

    @Value("${cache.event.detail.ttl}")
    private Duration eventDetailTtl;

    @Value("${cache.event.detail.max-size}")
    private long eventDetailMaxSize;

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.registerCustomCache(CacheNames.EVENT_LIST, Caffeine.newBuilder()
                .maximumSize(eventListMaxSize)
                .expireAfterWrite(eventListTtl)
                .build());
        cacheManager.registerCustomCache(CacheNames.EVENT_DETAIL, Caffeine.newBuilder()
                .maximumSize(eventDetailMaxSize)
                .expireAfterWrite(eventDetailTtl)
                .build());
        return cacheManager;
    }
}
