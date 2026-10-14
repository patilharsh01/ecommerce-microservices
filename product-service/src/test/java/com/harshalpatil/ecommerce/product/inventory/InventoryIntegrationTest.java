package com.harshalpatil.ecommerce.product.inventory;

import com.harshalpatil.ecommerce.common.messaging.Message;
import com.harshalpatil.ecommerce.common.messaging.Topics;
import com.harshalpatil.ecommerce.common.messaging.inventory.OrderLine;
import com.harshalpatil.ecommerce.common.messaging.inventory.ReserveStockCommand;
import com.harshalpatil.ecommerce.common.messaging.inventory.StockReservationFailed;
import com.harshalpatil.ecommerce.common.messaging.inventory.StockReserved;
import com.harshalpatil.ecommerce.platform.messaging.InboxProcessor;
import com.harshalpatil.ecommerce.platform.messaging.MessageCodec;
import com.harshalpatil.ecommerce.platform.messaging.MessageHeaders;
import com.harshalpatil.ecommerce.platform.testing.IntegrationTest;
import com.harshalpatil.ecommerce.product.catalog.Product;
import com.harshalpatil.ecommerce.product.catalog.ProductRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest(properties = {"spring.cache.type=simple", "eureka.client.enabled=false"})
class InventoryIntegrationTest {

    @Autowired
    ProductRepository products;
    @Autowired
    InventoryService inventory;
    @Autowired
    InboxProcessor inbox;
    @Autowired
    MessageCodec codec;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    KafkaTemplate<String, String> kafka;
    /** The Testcontainers broker; the spring.kafka.* properties would point at localhost:9092. */
    @Autowired
    KafkaConnectionDetails kafkaConnection;

    /**
     * 50 orders race for the last 10 units, each on its own thread and transaction.
     * Exactly 10 must win, stock must end at exactly 0, and nothing may deadlock.
     */
    @Test
    void neverOversellsUnderConcurrentReservations() throws Exception {
        Long productId = products.save(new Product("RACE-" + UUID.randomUUID().toString().substring(0, 8),
                "Contended item", null, new BigDecimal("10.00"), 10)).getId();
        Long otherId = products.save(new Product("RACE-" + UUID.randomUUID().toString().substring(0, 8),
                "Companion item", null, new BigDecimal("5.00"), 1_000)).getId();

        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Callable<Void>> orders = new ArrayList<>();
        for (long orderId = 10_000; orderId < 10_050; orderId++) {
            // Half the orders list the products in the opposite order to provoke lock-ordering deadlocks.
            List<OrderLine> lines = orderId % 2 == 0
                    ? List.of(new OrderLine(productId, 1), new OrderLine(otherId, 1))
                    : List.of(new OrderLine(otherId, 1), new OrderLine(productId, 1));
            ReserveStockCommand command = ReserveStockCommand.of(orderId, lines);
            orders.add(() -> {
                inbox.process(command, message -> inventory.reserve((ReserveStockCommand) message));
                return null;
            });
        }
        for (Future<Void> result : pool.invokeAll(orders, 60, TimeUnit.SECONDS)) {
            result.get();
        }
        pool.shutdown();

        assertThat(products.findById(productId).orElseThrow().getStockQuantity()).isZero();
        assertThat(products.findById(otherId).orElseThrow().getStockQuantity()).isEqualTo(990);
        assertThat(countReservations("RESERVED", 10_000, 10_049)).isEqualTo(10);
        assertThat(countReservations("REJECTED", 10_000, 10_049)).isEqualTo(40);
    }

    @Test
    void answersACommandFromKafkaOnTheRepliesTopic() throws Exception {
        // Two products, so the outcome doesn't depend on which partition is consumed first.
        Long inStock = products.save(new Product("KAFKA-" + UUID.randomUUID().toString().substring(0, 8),
                "Kafka item", null, new BigDecimal("10.00"), 2)).getId();
        Long soldOut = products.save(new Product("KAFKA-" + UUID.randomUUID().toString().substring(0, 8),
                "Sold-out item", null, new BigDecimal("10.00"), 0)).getId();

        try (KafkaConsumer<String, String> replies = repliesConsumer()) {
            send(ReserveStockCommand.of(20_001L, List.of(new OrderLine(inStock, 2))));
            send(ReserveStockCommand.of(20_002L, List.of(new OrderLine(soldOut, 1))));

            Map<String, Message> received = awaitReplies(replies, "20001", "20002");

            assertThat(received.get("20001")).isInstanceOf(StockReserved.class);
            assertThat(received.get("20002")).isInstanceOf(StockReservationFailed.class);
        }
    }

    private void send(ReserveStockCommand command) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(Topics.INVENTORY_COMMANDS,
                String.valueOf(command.orderId()), codec.encode(command));
        record.headers().add(MessageHeaders.MESSAGE_TYPE, codec.typeOf(command).getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(10, TimeUnit.SECONDS);
    }

    private KafkaConsumer<String, String> repliesConsumer() {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafkaConnection.getBootstrapServers()),
                ConsumerConfig.GROUP_ID_CONFIG, "replies-probe-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(Topics.ORDER_SAGA_REPLIES));
        return consumer;
    }

    private Map<String, Message> awaitReplies(KafkaConsumer<String, String> consumer, String... keys) {
        Map<String, Message> received = new java.util.HashMap<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (received.size() < keys.length && System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                if (List.of(keys).contains(record.key())) {
                    received.put(record.key(), codec.decode(record));
                }
            }
        }
        assertThat(received).as("replies received").hasSize(keys.length);
        return received;
    }

    private int countReservations(String status, long fromOrder, long toOrder) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM stock_reservations WHERE status = ? AND order_id BETWEEN ? AND ?",
                Integer.class, status, fromOrder, toOrder);
        return count == null ? 0 : count;
    }
}
