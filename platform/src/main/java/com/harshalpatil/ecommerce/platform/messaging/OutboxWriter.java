package com.harshalpatil.ecommerce.platform.messaging;

import com.harshalpatil.ecommerce.common.messaging.Message;
import com.harshalpatil.ecommerce.common.messaging.OrderMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes a message by inserting it into the {@code outbox_messages} table <em>inside the
 * caller's transaction</em>. The row commits or rolls back together with the state change it
 * describes, so "order saved but event lost" and "event sent for a rolled-back order" can't
 * happen. {@link OutboxRelay} moves committed rows to Kafka.
 */
public class OutboxWriter {

    private static final String INSERT = """
            INSERT INTO outbox_messages (message_id, topic, message_key, message_type, payload, headers, created_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, now())
            """;

    private final JdbcTemplate jdbc;
    private final MessageCodec codec;
    private final TraceHeaders traceHeaders;
    private final OutboxRelay relay;

    public OutboxWriter(JdbcTemplate jdbc, MessageCodec codec, TraceHeaders traceHeaders, OutboxRelay relay) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.traceHeaders = traceHeaders;
        this.relay = relay;
    }

    /** Keys the record by order id so every message about one order stays on one partition, in order. */
    public void send(String topic, OrderMessage message) {
        send(topic, String.valueOf(message.orderId()), message);
    }

    public void send(String topic, String key, Message message) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("OutboxWriter.send() needs an active transaction: the outbox row "
                    + "must commit atomically with the state change it announces");
        }
        jdbc.update(INSERT,
                message.messageId(),
                topic,
                key,
                codec.typeOf(message),
                codec.encode(message),
                codec.encodeHeaders(traceHeaders.current()));

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                relay.wakeUp();
            }
        });
    }
}
