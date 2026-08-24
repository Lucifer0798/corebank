package com.corebank.config;

import com.corebank.account.dto.AccountResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis is a read-through accelerator, never a source of truth: every value it holds also
 * lives in PostgreSQL, and a cache miss or a Redis outage just means falling back to a normal
 * query. The {@link CacheErrorHandler} below is what makes that true in practice -- without it,
 * a broken Redis connection would turn every cached read into a 500 instead of a cache miss.
 *
 * <p>Implementing {@link CachingConfigurer} rather than just declaring a {@code CacheErrorHandler}
 * bean is deliberate: Spring's caching AOP infrastructure only auto-detects an error handler
 * this way, and a version that merely declares the bean silently keeps the default handler,
 * which rethrows -- the exact failure mode this class exists to prevent.
 */
@EnableCaching
@Configuration
public class CacheConfig implements CachingConfigurer {

    private final CacheErrorHandler cacheErrorHandler = new LoggingCacheErrorHandler();

    @Override
    public CacheErrorHandler errorHandler() {
        return cacheErrorHandler;
    }

    public static final String ACCOUNTS_CACHE = "accounts";
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        RedisCacheConfiguration accountsConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(DEFAULT_TTL)
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        // Bound to AccountResponse specifically, not the generic polymorphic
                        // serializer: this cache only ever holds one type, and a serializer
                        // keyed to Object.class has no way to know at read time which concrete
                        // type to reconstruct without embedding type metadata in every entry.
                        // Reuses the application's own Jackson 3 ObjectMapper (JSR-310 module
                        // and all), so Instant/LocalDate fields serialize exactly as they do in
                        // an HTTP response.
                        new JacksonJsonRedisSerializer<>(objectMapper, AccountResponse.class)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(RedisCacheConfiguration.defaultCacheConfig())
                .withCacheConfiguration(ACCOUNTS_CACHE, accountsConfig)
                .build();
    }

    private static final class LoggingCacheErrorHandler implements CacheErrorHandler {

        private static final Logger log = LoggerFactory.getLogger(LoggingCacheErrorHandler.class);

        @Override
        public void handleCacheGetError(RuntimeException exception, org.springframework.cache.Cache cache, Object key) {
            log.warn("Cache read failed for '{}' [{}]; falling through to the database: {}",
                    cache.getName(), key, exception.toString());
        }

        @Override
        public void handleCachePutError(RuntimeException exception, org.springframework.cache.Cache cache, Object key,
                                        Object value) {
            log.warn("Cache write failed for '{}' [{}]: {}", cache.getName(), key, exception.toString());
        }

        @Override
        public void handleCacheEvictError(RuntimeException exception, org.springframework.cache.Cache cache, Object key) {
            log.warn("Cache evict failed for '{}' [{}]: {}", cache.getName(), key, exception.toString());
        }

        @Override
        public void handleCacheClearError(RuntimeException exception, org.springframework.cache.Cache cache) {
            log.warn("Cache clear failed for '{}': {}", cache.getName(), exception.toString());
        }
    }
}
