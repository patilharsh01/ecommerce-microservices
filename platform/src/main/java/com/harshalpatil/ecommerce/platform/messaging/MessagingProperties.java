package com.harshalpatil.ecommerce.platform.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * @param topics            topics this service consumes or owns; each is created together with
 *                          its dead-letter twin
 * @param partitions        partitions per topic — the upper bound on consumer parallelism
 * @param replicationFactor 1 for local development, 3 in any real cluster
 */
@ConfigurationProperties("platform.messaging")
public record MessagingProperties(
        @DefaultValue Outbox outbox,
        @DefaultValue Inbox inbox,
        @DefaultValue Retry retry,
        @DefaultValue List<String> topics,
        @DefaultValue("3") int partitions,
        @DefaultValue("1") short replicationFactor) {

    /**
     * @param pollInterval safety-net polling; normally the relay is woken right after each commit
     * @param retention    how long published rows are kept for debugging before being purged
     * @param maxAttempts  failed sends after which a row is parked for an operator instead of
     *                     being retried forever
     */
    public record Outbox(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1s") Duration pollInterval,
            @DefaultValue("200") int batchSize,
            @DefaultValue("10s") Duration sendTimeout,
            @DefaultValue("3d") Duration retention,
            @DefaultValue("20") int maxAttempts) {
    }

    /** @param retention must comfortably exceed the Kafka topic retention, or a very late redelivery could slip past de-duplication */
    public record Inbox(@DefaultValue("14d") Duration retention) {
    }

    /** Back-off for a failing record before it's dead-lettered: 0.5s, 1s, 2s, 4s by default. */
    public record Retry(
            @DefaultValue("500ms") Duration initialInterval,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("4") int maxRetries) {
    }
}
