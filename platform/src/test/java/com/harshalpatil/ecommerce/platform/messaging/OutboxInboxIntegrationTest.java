package com.harshalpatil.ecommerce.platform.messaging;

import com.harshalpatil.ecommerce.common.messaging.Message;
import com.harshalpatil.ecommerce.common.messaging.OrderMessage;
import com.harshalpatil.ecommerce.common.messaging.inventory.StockReleased;
import com.harshalpatil.ecommerce.common.messaging.inventory.StockReserved;
import com.harshalpatil.ecommerce.platform.testing.IntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@IntegrationTest(properties = {
        "spring.application.name=platform-test",
        "spring.flyway.locations=classpath:db/platform/outbox,classpath:db/platform/inbox",
        "platform.messaging.topics=" + PlatformTestApplication.TOPIC + "," + PlatformTestApplication.POISON_TOPIC,
        "platform.messaging.retry.max-retries=1",
        "platform.messaging.retry.initial-interval=50ms",
        "spring.kafka.listener.concurrency=1"
})
class OutboxInboxIntegrationTest {

    @Autowired
    OutboxWriter outbox;
    @Autowired
    InboxProcessor inbox;
    @Autowired
    MessageCodec codec;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    PlatformTestApplication.RecordingListener recorder;
    @Autowired
    PlatformTestApplication.InboxBackedListener inboxListener;
    @Autowired
    PlatformTransactionManager transactionManager;

    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
    }

    @Test
    void refusesToWriteOutsideATransaction() {
        assertThatThrownBy(() -> outbox.send(PlatformTestApplication.TOPIC, StockReserved.of(1L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active transaction");
    }

    @Test
    void committedMessageReachesKafkaKeyedByOrderWithIdAndTypeHeaders() throws Exception {
        StockReserved message = StockReserved.of(100L);

        tx.executeWithoutResult(status -> outbox.send(PlatformTestApplication.TOPIC, message));

        ConsumerRecord<String, String> record = awaitRecord("100");
        assertThat(header(record, MessageHeaders.MESSAGE_ID)).isEqualTo(message.messageId().toString());
        assertThat(header(record, MessageHeaders.MESSAGE_TYPE)).isEqualTo(StockReserved.class.getName());
        assertThat(codec.decode(record)).isEqualTo(message);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT published_at IS NOT NULL FROM outbox_messages WHERE message_id = ?",
                Boolean.class, message.messageId())).isTrue());
    }

    @Test
    void rolledBackMessageIsNeverPublished() throws Exception {
        StockReserved doomed = StockReserved.of(200L);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            outbox.send(PlatformTestApplication.TOPIC, doomed);
            throw new IllegalStateException("business rule failed after the message was written");
        })).isInstanceOf(IllegalStateException.class);
        tx.executeWithoutResult(status -> outbox.send(PlatformTestApplication.TOPIC, StockReserved.of(201L)));

        List<ConsumerRecord<String, String>> seen = awaitRecordCollectingOthers("201");
        assertThat(seen).noneMatch(record -> "200".equals(record.key()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_messages WHERE message_id = ?",
                Integer.class, doomed.messageId())).isZero();
    }

    @Test
    void messagesForOneKeyArriveInTheOrderTheyWereCommitted() throws Exception {
        List<OrderMessage> sent = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            OrderMessage message = i % 2 == 0 ? StockReserved.of(300L) : StockReleased.of(300L, true);
            sent.add(message);
            tx.executeWithoutResult(status -> outbox.send(PlatformTestApplication.TOPIC, message));
        }

        List<Message> received = new ArrayList<>();
        while (received.size() < sent.size()) {
            ConsumerRecord<String, String> record = recorder.received.poll(15, TimeUnit.SECONDS);
            assertThat(record).as("timed out waiting for message %d", received.size()).isNotNull();
            if ("300".equals(record.key())) {
                received.add(codec.decode(record));
            }
        }
        assertThat(received).containsExactlyElementsOf(sent);
    }

    @Test
    void duplicateDeliveryRunsTheHandlerOnce() {
        StockReserved message = StockReserved.of(400L);
        AtomicInteger calls = new AtomicInteger();

        inbox.process(message, m -> calls.incrementAndGet());
        inbox.process(message, m -> calls.incrementAndGet());

        assertThat(calls).hasValue(1);
        assertThat(inbox.alreadyProcessed(message)).isTrue();
    }

    @Test
    void failedHandlerLeavesNoInboxRowSoTheRetryRunsAgain() {
        StockReserved message = StockReserved.of(500L);
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> inbox.process(message, m -> {
            calls.incrementAndGet();
            throw new IllegalStateException("transient failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(inbox.alreadyProcessed(message)).isFalse();

        inbox.process(message, m -> calls.incrementAndGet());

        assertThat(calls).hasValue(2);
    }

    @Test
    void undecodableRecordGoesStraightToTheDeadLetterTopicWithoutBlockingTheNextOne() throws Exception {
        ProducerRecord<String, String> poison = new ProducerRecord<>(PlatformTestApplication.POISON_TOPIC, "600", "{not json");
        poison.headers().add(MessageHeaders.MESSAGE_TYPE, StockReserved.class.getName().getBytes(StandardCharsets.UTF_8));
        StockReserved healthy = StockReserved.of(600L);
        ProducerRecord<String, String> next = new ProducerRecord<>(PlatformTestApplication.POISON_TOPIC, "600", codec.encode(healthy));
        next.headers().add(MessageHeaders.MESSAGE_TYPE, StockReserved.class.getName().getBytes(StandardCharsets.UTF_8));

        kafka.send(poison).get(10, TimeUnit.SECONDS);
        kafka.send(next).get(10, TimeUnit.SECONDS);

        ConsumerRecord<String, String> deadLetter = recorder.deadLetters.poll(30, TimeUnit.SECONDS);
        assertThat(deadLetter).isNotNull();
        assertThat(deadLetter.value()).isEqualTo("{not json");
        assertThat(header(deadLetter, "kafka_dlt-exception-fqcn")).isNotBlank();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(inboxListener.handled).contains(healthy));
    }

    private ConsumerRecord<String, String> awaitRecord(String key) throws InterruptedException {
        List<ConsumerRecord<String, String>> seen = awaitRecordCollectingOthers(key);
        return seen.get(seen.size() - 1);
    }

    /** Polls until a record with {@code key} arrives; returns everything seen, that record last. */
    private List<ConsumerRecord<String, String>> awaitRecordCollectingOthers(String key) throws InterruptedException {
        List<ConsumerRecord<String, String>> seen = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            ConsumerRecord<String, String> record = recorder.received.poll(1, TimeUnit.SECONDS);
            if (record != null) {
                seen.add(record);
                if (key.equals(record.key())) {
                    return seen;
                }
            }
        }
        throw new AssertionError("No record with key " + key + " within 20s; saw " + seen.size() + " others");
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
