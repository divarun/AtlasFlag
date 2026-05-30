package com.atlasflag.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Value("${atlasflag.cache.ttl:300}")
    private long cacheTtl;

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("flags");
        manager.setCaffeine(Caffeine.newBuilder()
            .expireAfterWrite(cacheTtl, TimeUnit.SECONDS)
            .maximumSize(1000));
        return manager;
    }
}
