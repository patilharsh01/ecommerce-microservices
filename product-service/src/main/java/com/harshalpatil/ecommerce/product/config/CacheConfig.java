package com.harshalpatil.ecommerce.product.config;

import com.harshalpatil.ecommerce.product.catalog.ProductResponse;
import com.harshalpatil.ecommerce.product.catalog.ProductService;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableCaching
class CacheConfig {

    /**
     * Product entries are stored as readable JSON (handy with {@code redis-cli}) rather than
     * Java serialization, and evictions are deferred until the evicting transaction commits.
     * <p>
     * Eviction alone can't rule out a stale entry: a read that loaded the old row can still put
     * it into the cache just after a reservation evicted it. The short TTL bounds how long such
     * an entry can show an outdated stock level. It only affects display — order pricing and
     * stock checks always read the database.
     */
    @Bean
    RedisCacheManagerBuilderCustomizer productCacheCustomizer() {
        return builder -> builder
                .transactionAware()
                .withCacheConfiguration(ProductService.CACHE, RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(Duration.ofSeconds(30))
                        .disableCachingNullValues()
                        .serializeValuesWith(SerializationPair.fromSerializer(
                                new JacksonJsonRedisSerializer<>(ProductResponse.class))));
    }
}
