package com.harshalpatil.ecommerce.platform.messaging;

import com.harshalpatil.ecommerce.common.messaging.Message;
import com.harshalpatil.ecommerce.common.messaging.OrderMessage;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Idempotent consumer. Kafka delivers at least once — after a rebalance or a crash between
 * "commit DB transaction" and "commit offset" a record comes back. Each message id is recorded
 * in {@code inbox_messages} in the same transaction as the handler's side effects, so a
 * redelivered message is recognised and skipped, and a handler that fails leaves no trace
 * (the inbox row rolls back with everything else and the retry starts clean).
 */
@Slf4j
public class InboxProcessor {

    private static final String MARK_PROCESSED = """
            INSERT INTO inbox_messages (message_id, consumer, message_type, processed_at)
            VALUES (?, ?, ?, now())
            ON CONFLICT DO NOTHING
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final MessageCodec codec;
    private final MessagingProperties.Inbox properties;
    private final MeterRegistry meterRegistry;
    private final String consumerName;

    public InboxProcessor(JdbcTemplate jdbc, TransactionTemplate transactionTemplate, MessageCodec codec,
                          MessagingProperties.Inbox properties, MeterRegistry meterRegistry, String consumerName) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.codec = codec;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.consumerName = consumerName;
    }

    public Message decode(ConsumerRecord<String, String> record) {
        return codec.decode(record);
    }

    /** Decodes the record and runs {@code handler} exactly once per message id, in one transaction. */
    public void process(ConsumerRecord<String, String> record, Consumer<Message> handler) {
        process(decode(record), handler);
    }

    public void process(Message message, Consumer<Message> handler) {
        try (var ignored = withLoggingContext(message)) {
            transactionTemplate.executeWithoutResult(status -> {
                int inserted = jdbc.update(MARK_PROCESSED, message.messageId(), consumerName, codec.typeOf(message));
                if (inserted == 0) {
                    log.info("Skipping duplicate {} {}", type(message), message.messageId());
                    count("inbox.messages.duplicate", message);
                    return;
                }
                handler.accept(message);
                count("inbox.messages.processed", message);
            });
        }
    }

    /**
     * Read-only pre-check for handlers that must do slow, non-transactional work (like calling
     * a payment provider) before opening the transaction. {@link #process} still re-checks
     * atomically, so this is only an optimisation.
     */
    public boolean alreadyProcessed(Message message) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM inbox_messages WHERE message_id = ? AND consumer = ?",
                Integer.class, message.messageId(), consumerName);
        return count != null && count > 0;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    public void purgeExpired() {
        Instant cutoff = Instant.now().minus(properties.retention());
        int deleted = jdbc.update("DELETE FROM inbox_messages WHERE processed_at < ?", Timestamp.from(cutoff));
        if (deleted > 0) {
            log.info("Purged {} inbox rows older than {}", deleted, properties.retention());
        }
    }

    private void count(String meter, Message message) {
        meterRegistry.counter(meter, "type", type(message)).increment();
    }

    private static String type(Message message) {
        return message.getClass().getSimpleName();
    }

    /** Tags every log line written while handling the message with its id and order. */
    private static LoggingContext withLoggingContext(Message message) {
        MDC.put("messageId", String.valueOf(message.messageId()));
        if (message instanceof OrderMessage orderMessage) {
            MDC.put("orderId", String.valueOf(orderMessage.orderId()));
        }
        return () -> {
            MDC.remove("messageId");
            MDC.remove("orderId");
        };
    }

    private interface LoggingContext extends AutoCloseable {
        @Override
        void close();
    }
}
