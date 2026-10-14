package com.harshalpatil.ecommerce.platform.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Moves committed outbox rows to Kafka, oldest first.
 * <ul>
 *   <li><b>Push, with polling as a safety net.</b> {@link OutboxWriter} wakes the relay right
 *       after each commit, so messages usually leave within milliseconds. A slow fixed-delay
 *       poll picks up anything missed (e.g. rows written just before a restart).</li>
 *   <li><b>One relay at a time.</b> Each batch runs under a Postgres transaction-scoped advisory
 *       lock, so with several replicas exactly one publishes at any moment.</li>
 *   <li><b>At most one unacknowledged message per partition key.</b> Within a pass only the
 *       oldest unpublished row of each (topic, key) is sent; the next one goes in a later pass,
 *       after the first was acknowledged. A failed send can therefore never let a later message
 *       for the same order overtake it on its topic, and rows of other keys that were
 *       acknowledged are kept as published.</li>
 *   <li><b>At-least-once.</b> A row is marked published only after the broker acknowledged it.
 *       Anything else is retried on a later pass, and consumers de-duplicate.</li>
 *   <li><b>A poison row can't stall the outbox.</b> After {@code maxAttempts} failures a row is
 *       parked (along with later rows of its topic and key, to keep their order) and the rest
 *       flows on.
 *       Parked rows are counted in {@code outbox.messages.parked}, which alerts.</li>
 *   <li><b>Bounded passes.</b> The first send that fails synchronously (broker metadata
 *       unavailable) ends the pass, so the transaction and advisory lock aren't held for
 *       {@code max.block.ms} per row.</li>
 * </ul>
 */
@Slf4j
public class OutboxRelay implements SmartLifecycle {

    /** Arbitrary but fixed: identifies "the outbox relay" lock within each service's database. */
    private static final long ADVISORY_LOCK_KEY = 7_340_181_005L;

    private static final String SELECT_BATCH = """
            SELECT id, message_id, topic, message_key, message_type, payload::text AS payload, headers::text AS headers
            FROM outbox_messages
            WHERE published_at IS NULL
              AND attempts < :maxAttempts
              AND (topic, message_key) NOT IN (
                  SELECT topic, message_key FROM outbox_messages WHERE published_at IS NULL AND attempts >= :maxAttempts)
            ORDER BY id
            LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final KafkaTemplate<String, String> kafka;
    private final MessageCodec codec;
    private final MessagingProperties.Outbox properties;
    private final Counter publishedCounter;
    private final Counter failureCounter;

    private final AtomicBoolean drainQueued = new AtomicBoolean();
    private volatile ScheduledExecutorService executor;

    public OutboxRelay(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactionTemplate,
                       KafkaTemplate<String, String> kafka, MessageCodec codec,
                       MessagingProperties.Outbox properties, MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.kafka = kafka;
        this.codec = codec;
        this.properties = properties;
        this.publishedCounter = Counter.builder("outbox.messages.published")
                .description("Outbox rows acknowledged by Kafka")
                .register(meterRegistry);
        this.failureCounter = Counter.builder("outbox.publish.failures")
                .description("Outbox rows Kafka failed to acknowledge (they are retried)")
                .register(meterRegistry);
        Gauge.builder("outbox.messages.pending", this, relay -> relay.count("published_at IS NULL"))
                .description("Committed outbox rows not yet acknowledged by Kafka")
                .register(meterRegistry);
        Gauge.builder("outbox.messages.parked", this,
                        relay -> relay.count("published_at IS NULL AND attempts >= " + properties.maxAttempts()))
                .description("Outbox rows that failed too often and need an operator")
                .register(meterRegistry);
        Gauge.builder("outbox.oldest.pending.age", this, OutboxRelay::oldestPendingAgeSeconds)
                .description("Age in seconds of the oldest unpublished outbox row (0 when empty)")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    /**
     * Asks for a drain soon. Cheap, and coalesced: many commits in a burst trigger one pass.
     * Never throws: it runs in after-commit callbacks, where an exception would turn an already
     * committed request into an error. During shutdown the next poll picks the rows up.
     */
    public void wakeUp() {
        ScheduledExecutorService current = executor;
        if (current == null || !drainQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            current.execute(() -> {
                drainQueued.set(false);
                drain();
            });
        } catch (RejectedExecutionException ex) {
            drainQueued.set(false);
        }
    }

    void drain() {
        try {
            Pass pass;
            do {
                pass = publishBatch();
            } while (pass.moreWaiting());
        } catch (RuntimeException ex) {
            log.warn("Outbox relay pass failed, will retry: {}", ex.getMessage());
        }
    }

    Pass publishBatch() {
        Pass pass = transactionTemplate.execute(status -> {
            Boolean locked = jdbc.getJdbcTemplate()
                    .queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, ADVISORY_LOCK_KEY);
            if (!Boolean.TRUE.equals(locked)) {
                return Pass.NOTHING;
            }

            List<OutboxRow> batch = jdbc.query(SELECT_BATCH,
                    Map.of("limit", properties.batchSize(), "maxAttempts", properties.maxAttempts()),
                    (rs, i) -> new OutboxRow(rs.getLong("id"), rs.getString("message_id"), rs.getString("topic"),
                            rs.getString("message_key"), rs.getString("message_type"), rs.getString("payload"),
                            rs.getString("headers")));
            if (batch.isEmpty()) {
                return Pass.NOTHING;
            }

            // Hand one row per (topic, key) to the producer so sends pipeline, then collect acknowledgements.
            Set<String> keysInFlight = new HashSet<>();
            List<InFlight> inFlight = new ArrayList<>();
            boolean deferred = false;
            boolean failed = false;
            for (OutboxRow row : batch) {
                if (!keysInFlight.add(row.topic() + '|' + row.messageKey())) {
                    deferred = true;
                    continue;
                }
                try {
                    inFlight.add(new InFlight(row, kafka.send(record(row))));
                } catch (RuntimeException ex) {
                    recordFailure(row, ex);
                    failed = true;
                    break;
                }
            }

            long deadline = System.nanoTime() + properties.sendTimeout().toNanos();
            List<Long> acknowledged = new ArrayList<>(inFlight.size());
            for (InFlight send : inFlight) {
                try {
                    send.result().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    acknowledged.add(send.row().id());
                } catch (Exception ex) {
                    if (ex instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    recordFailure(send.row(), ex);
                    failed = true;
                }
            }

            if (!acknowledged.isEmpty()) {
                jdbc.update("UPDATE outbox_messages SET published_at = now() WHERE id IN (:ids)",
                        Map.of("ids", acknowledged));
                publishedCounter.increment(acknowledged.size());
            }
            boolean moreWaiting = !failed && (deferred || batch.size() == properties.batchSize());
            return new Pass(acknowledged.size(), moreWaiting);
        });
        return pass == null ? Pass.NOTHING : pass;
    }

    private ProducerRecord<String, String> record(OutboxRow row) {
        RecordHeaders headers = new RecordHeaders();
        headers.add(MessageHeaders.MESSAGE_ID, row.messageId().getBytes(StandardCharsets.UTF_8));
        headers.add(MessageHeaders.MESSAGE_TYPE, row.messageType().getBytes(StandardCharsets.UTF_8));
        codec.decodeHeaders(row.headers())
                .forEach((name, value) -> headers.add(name, value.getBytes(StandardCharsets.UTF_8)));
        return new ProducerRecord<>(row.topic(), null, row.messageKey(), row.payload(), headers);
    }

    private void recordFailure(OutboxRow row, Exception ex) {
        failureCounter.increment();
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        Integer attempts = jdbc.queryForObject("""
                UPDATE outbox_messages SET attempts = attempts + 1, last_error = :error
                WHERE id = :id
                RETURNING attempts
                """, new MapSqlParameterSource()
                .addValue("id", row.id())
                .addValue("error", truncate(cause.toString())), Integer.class);
        if (attempts != null && attempts >= properties.maxAttempts()) {
            log.error("Parked outbox message {} for {} (key {}) after {} failed attempts; later messages for "
                    + "the same key wait behind it. Last error: {}", row.messageId(), row.topic(), row.messageKey(),
                    attempts, cause.toString());
        } else {
            log.warn("Kafka did not acknowledge outbox message {} for topic {}: {}",
                    row.messageId(), row.topic(), cause.toString());
        }
    }

    void purgePublished() {
        Instant cutoff = Instant.now().minus(properties.retention());
        int deleted = jdbc.update("DELETE FROM outbox_messages WHERE published_at < :cutoff",
                Map.of("cutoff", Timestamp.from(cutoff)));
        if (deleted > 0) {
            log.info("Purged {} published outbox rows older than {}", deleted, properties.retention());
        }
    }

    private double count(String condition) {
        try {
            Long count = jdbc.getJdbcTemplate()
                    .queryForObject("SELECT count(*) FROM outbox_messages WHERE " + condition, Long.class);
            return count == null ? 0 : count;
        } catch (RuntimeException ex) {
            return Double.NaN;
        }
    }

    private double oldestPendingAgeSeconds() {
        try {
            Double age = jdbc.getJdbcTemplate().queryForObject("""
                    SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0)::float8
                    FROM outbox_messages WHERE published_at IS NULL
                    """, Double.class);
            return age == null ? 0 : age;
        } catch (RuntimeException ex) {
            return Double.NaN;
        }
    }

    private static String truncate(String value) {
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    @Override
    public void start() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "outbox-relay");
            thread.setDaemon(true);
            return thread;
        });
        long pollMillis = properties.pollInterval().toMillis();
        scheduler.scheduleWithFixedDelay(this::drain, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::purgeSafely, 1, 60, TimeUnit.MINUTES);
        executor = scheduler;
        log.info("Outbox relay started (poll every {}, batch {})", properties.pollInterval(), properties.batchSize());
    }

    private void purgeSafely() {
        try {
            purgePublished();
        } catch (RuntimeException ex) {
            log.warn("Outbox purge failed: {}", ex.getMessage());
        }
    }

    @Override
    public void stop() {
        ScheduledExecutorService current = executor;
        executor = null;
        if (current == null) {
            return;
        }
        current.shutdown();
        try {
            if (!current.awaitTermination(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException ex) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }

    /** Start after the Kafka producer and datasource exist; stop before them. */
    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 1024;
    }

    /** @param moreWaiting rows were left behind on purpose (deferred keys or a full batch): go again now */
    record Pass(int published, boolean moreWaiting) {
        static final Pass NOTHING = new Pass(0, false);
    }

    private record InFlight(OutboxRow row, CompletableFuture<SendResult<String, String>> result) {
    }

    record OutboxRow(long id, String messageId, String topic, String messageKey, String messageType,
                             String payload, String headers) {
    }
}
