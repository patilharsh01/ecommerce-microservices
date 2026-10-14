package com.harshalpatil.ecommerce.order.catalog;

import com.harshalpatil.ecommerce.platform.web.DependencyUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Prices an order against the live catalog — the one synchronous call in the order flow.
 * <ul>
 *   <li>One batch request per order instead of one per line item.</li>
 *   <li>A retry (one extra attempt) for transient network errors and 5xx answers; the lookup
 *       is a read, so retrying is safe.</li>
 *   <li>A circuit breaker around both: once the catalog is clearly down, orders fail fast
 *       with 503 instead of each waiting out the timeouts.</li>
 * </ul>
 */
@Service
@Slf4j
public class PricingService {

    private final CatalogClient catalog;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public PricingService(CatalogClient catalog, MeterRegistry meterRegistry) {
        this.catalog = catalog;

        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .slowCallDurationThreshold(Duration.ofSeconds(2))
                .slowCallRateThreshold(80)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build());
        RetryRegistry retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(200))
                .retryExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .build());
        this.circuitBreaker = breakers.circuitBreaker("catalog");
        this.retry = retries.retry("catalog");

        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(meterRegistry);
        TaggedRetryMetrics.ofRetryRegistry(retries).bindTo(meterRegistry);
    }

    /** @return the catalog entry for each id that exists; unknown ids are simply missing from the map */
    public Map<Long, CatalogProduct> lookup(Collection<Long> productIds) {
        Supplier<Map<Long, CatalogProduct>> call = () -> catalog.lookup(productIds).stream()
                .collect(Collectors.toMap(CatalogProduct::id, Function.identity()));
        try {
            return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, call)).get();
        } catch (CallNotPermittedException ex) {
            throw new DependencyUnavailableException("The product catalog", ex);
        } catch (RestClientException | IllegalStateException ex) {
            // IllegalStateException: the load balancer found no healthy product-service instance.
            log.warn("Catalog lookup failed: {}", ex.toString());
            throw new DependencyUnavailableException("The product catalog", ex);
        }
    }
}
