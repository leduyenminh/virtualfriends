package com.vchat.config;

import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import java.time.Duration;

/**
 * Two cache layers, each where it fits:
 *  - Redis (Spring Cache): shared by all backend instances, survives restarts. Used for expensive external calls
 *    (TTS audio, voice catalogues, provider health), plus presence and rate limits (see PresenceService /
 *    RateLimitAspect).
 *  - Ehcache (Hibernate 2nd-level cache, ehcache.xml): in-JVM, for immutable/read-mostly entities.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {
    public static final String TTS = "tts";
    public static final String TTS_VOICES = "ttsVoices";
    public static final String TTS_PROVIDERS = "ttsProviders";

    @Bean
    RedisCacheManagerBuilderCustomizer cacheTtls() {
        return b -> b
                .withCacheConfiguration(TTS, ttl(Duration.ofDays(7)))
                .withCacheConfiguration(TTS_VOICES, ttl(Duration.ofHours(1)))
                .withCacheConfiguration(TTS_PROVIDERS, ttl(Duration.ofSeconds(30)));
    }

    private static RedisCacheConfiguration ttl(Duration d) {
        return RedisCacheConfiguration.defaultCacheConfig().entryTtl(d).disableCachingNullValues();
    }

    /** A cache outage must never break the app: log and fall through to the real method. */
    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }
}
