package com.harshalpatil.ecommerce.payment;

import com.harshalpatil.ecommerce.common.messaging.Message;
import com.harshalpatil.ecommerce.common.messaging.OrderMessage;
import com.harshalpatil.ecommerce.common.messaging.Topics;
import com.harshalpatil.ecommerce.common.messaging.payment.PaymentCompleted;
import com.harshalpatil.ecommerce.common.messaging.payment.PaymentDeclined;
import com.harshalpatil.ecommerce.common.messaging.payment.PaymentRefunded;
import com.harshalpatil.ecommerce.common.messaging.payment.ProcessPaymentCommand;
import com.harshalpatil.ecommerce.common.messaging.payment.RefundPaymentCommand;
import com.harshalpatil.ecommerce.platform.messaging.MessageCodec;
import com.harshalpatil.ecommerce.platform.messaging.MessageHeaders;
import com.harshalpatil.ecommerce.platform.testing.IntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Commands in over Kafka, replies out over Kafka, through the real outbox and inbox. */
@IntegrationTest(properties = {"eureka.client.enabled=false", "payment.simulation.approval-limit=1000"})
class PaymentSagaIntegrationTest {

    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    MessageCodec codec;
    /** The Testcontainers broker; the spring.kafka.* properties would point at localhost:9092. */
    @Autowired
    KafkaConnectionDetails kafkaConnection;

    @Test
    void chargesRefundsAndVoidsOverKafka() throws Exception {
        try (KafkaConsumer<String, String> replies = repliesConsumer()) {
            send(ProcessPaymentCommand.of(1L, 7L, new BigDecimal("500.00")));
            send(ProcessPaymentCommand.of(2L, 7L, new BigDecimal("5000.00")));
            send(RefundPaymentCommand.of(1L, "customer cancelled"));
            send(RefundPaymentCommand.of(3L, "saga timed out"));
            send(ProcessPaymentCommand.of(3L, 7L, new BigDecimal("10.00")));

            List<OrderMessage> received = await(replies, 5);

            assertThat(received).filteredOn(m -> m.orderId() == 1L)
                    .hasExactlyElementsOfTypes(PaymentCompleted.class, PaymentRefunded.class);
            assertThat(received).filteredOn(m -> m.orderId() == 2L).hasExactlyElementsOfTypes(PaymentDeclined.class);
            assertThat(received).filteredOn(m -> m.orderId() == 3L)
                    .as("voided before the charge arrived: never charged")
                    .hasExactlyElementsOfTypes(PaymentRefunded.class, PaymentDeclined.class);
        }
    }

    private void send(OrderMessage command) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(Topics.PAYMENT_COMMANDS,
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

    private List<OrderMessage> await(KafkaConsumer<String, String> consumer, int expected) {
        List<OrderMessage> received = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (received.size() < expected && System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                Message message = codec.decode(record);
                received.add((OrderMessage) message);
            }
        }
        assertThat(received).hasSize(expected);
        return received;
    }
}
