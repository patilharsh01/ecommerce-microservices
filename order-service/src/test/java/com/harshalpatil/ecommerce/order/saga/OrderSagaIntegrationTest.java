package com.harshalpatil.ecommerce.order.saga;

import com.harshalpatil.ecommerce.common.messaging.Message;
import com.harshalpatil.ecommerce.common.messaging.OrderMessage;
import com.harshalpatil.ecommerce.common.messaging.Topics;
import com.harshalpatil.ecommerce.common.messaging.inventory.ReleaseStockCommand;
import com.harshalpatil.ecommerce.common.messaging.inventory.ReserveStockCommand;
import com.harshalpatil.ecommerce.common.messaging.inventory.StockReleased;
import com.harshalpatil.ecommerce.common.messaging.inventory.StockReservationFailed;
import com.harshalpatil.ecommerce.common.messaging.inventory.StockReserved;
import com.harshalpatil.ecommerce.common.messaging.payment.PaymentCompleted;
import com.harshalpatil.ecommerce.common.messaging.payment.PaymentDeclined;
import com.harshalpatil.ecommerce.common.messaging.payment.PaymentRefunded;
import com.harshalpatil.ecommerce.common.messaging.payment.ProcessPaymentCommand;
import com.harshalpatil.ecommerce.common.messaging.payment.RefundPaymentCommand;
import com.harshalpatil.ecommerce.common.security.Role;
import com.harshalpatil.ecommerce.order.api.OrderResponse;
import com.harshalpatil.ecommerce.order.api.OrderService;
import com.harshalpatil.ecommerce.order.api.PlaceOrderRequest;
import com.harshalpatil.ecommerce.order.catalog.CatalogClient;
import com.harshalpatil.ecommerce.order.catalog.CatalogProduct;
import com.harshalpatil.ecommerce.order.domain.CancellationCause;
import com.harshalpatil.ecommerce.order.domain.OrderStatus;
import com.harshalpatil.ecommerce.order.domain.SagaStep;
import com.harshalpatil.ecommerce.platform.messaging.MessageCodec;
import com.harshalpatil.ecommerce.platform.messaging.MessageHeaders;
import com.harshalpatil.ecommerce.platform.testing.IntegrationTest;
import com.harshalpatil.ecommerce.platform.web.AuthenticatedUser;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * The orchestrator against real Postgres and Kafka. Inventory and payments are played by
 * {@link FakeParticipants}, scripted through product ids and amounts:
 * product {@value #OUT_OF_STOCK} is never in stock; amounts above {@value #CARD_LIMIT} are
 * declined; an amount of {@value #SLOW_AMOUNT} gets no answer until the refund arrives —
 * at which point the late charge and then the refund are reported, in that order.
 */
@IntegrationTest(properties = {
        "eureka.client.enabled=false",
        "order.saga.step-timeout=3s",
        "order.saga.sweep-interval=500ms"
})
class OrderSagaIntegrationTest {

    static final long IN_STOCK = 1L;
    static final long OUT_OF_STOCK = 2L;
    static final long EXPENSIVE = 3L;
    static final long SLOW = 4L;
    static final String CARD_LIMIT = "50000";
    static final String SLOW_AMOUNT = "777.00";

    private static final AuthenticatedUser CUSTOMER = new AuthenticatedUser(7L, Set.of(Role.CUSTOMER));

    @MockitoBean
    CatalogClient catalog;
    @Autowired
    OrderService orderService;
    @Autowired
    FakeParticipants participants;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void catalogPrices() {
        when(catalog.lookup(anyCollection())).thenAnswer(call -> {
            Map<Long, CatalogProduct> all = Map.of(
                    IN_STOCK, new CatalogProduct(IN_STOCK, "KB", "Keyboard", new BigDecimal("1000.00"), 10, true),
                    OUT_OF_STOCK, new CatalogProduct(OUT_OF_STOCK, "GONE", "Sold-out item", new BigDecimal("10.00"), 0, true),
                    EXPENSIVE, new CatalogProduct(EXPENSIVE, "CAR", "Very expensive item", new BigDecimal("90000.00"), 1, true),
                    SLOW, new CatalogProduct(SLOW, "SLOW", "Slow-payment item", new BigDecimal(SLOW_AMOUNT), 5, true));
            return call.<java.util.Collection<Long>>getArgument(0).stream().map(all::get).filter(java.util.Objects::nonNull).toList();
        });
    }

    @Test
    void happyPathReservesChargesAndConfirms() {
        OrderResponse placed = place(IN_STOCK, 2);

        OrderResponse finished = awaitStatus(placed.id(), OrderStatus.CONFIRMED);

        assertThat(finished.paymentReference()).startsWith("PAY-");
        assertThat(steps(finished)).containsExactly(SagaStep.ORDER_PLACED, SagaStep.RESERVE_STOCK_SENT,
                SagaStep.STOCK_RESERVED, SagaStep.PROCESS_PAYMENT_SENT, SagaStep.PAYMENT_COMPLETED, SagaStep.ORDER_CONFIRMED);
    }

    @Test
    void outOfStockCancelsWithoutTouchingPayments() {
        OrderResponse placed = place(OUT_OF_STOCK, 1);

        OrderResponse finished = awaitStatus(placed.id(), OrderStatus.CANCELLED);

        assertThat(finished.cancellationCause()).isEqualTo(CancellationCause.OUT_OF_STOCK);
        assertThat(participants.paymentCommandsFor(placed.id())).isEmpty();
    }

    @Test
    void declinedPaymentReleasesTheReservedStock() {
        OrderResponse placed = place(EXPENSIVE, 1);

        OrderResponse finished = awaitStatus(placed.id(), OrderStatus.CANCELLED);

        assertThat(finished.cancellationCause()).isEqualTo(CancellationCause.PAYMENT_DECLINED);
        assertThat(participants.inventoryCommandsFor(placed.id()))
                .hasExactlyElementsOfTypes(ReserveStockCommand.class, ReleaseStockCommand.class);
        assertThat(steps(finished)).contains(SagaStep.PAYMENT_DECLINED, SagaStep.STOCK_RELEASED)
                .doesNotContain(SagaStep.REFUND_PAYMENT_SENT);
    }

    @Test
    void stalledPaymentTimesOutAndTheLateChargeIsRefunded() {
        OrderResponse placed = place(SLOW, 1);

        OrderResponse finished = awaitStatus(placed.id(), OrderStatus.CANCELLED);

        assertThat(finished.cancellationCause()).isEqualTo(CancellationCause.TIMED_OUT);
        assertThat(participants.paymentCommandsFor(placed.id()))
                .hasExactlyElementsOfTypes(ProcessPaymentCommand.class, RefundPaymentCommand.class);
        assertThat(steps(finished)).contains(SagaStep.COMPENSATION_STARTED, SagaStep.LATE_REPLY_IGNORED,
                SagaStep.PAYMENT_REFUNDED, SagaStep.STOCK_RELEASED, SagaStep.ORDER_CANCELLED);
        assertThat(finished.timeline()).filteredOn(entry -> entry.step() == SagaStep.PAYMENT_REFUNDED)
                .singleElement().satisfies(entry -> assertThat(entry.detail()).contains("Refunded"));
    }

    @Test
    void concurrentRetriesWithOneIdempotencyKeyCreateExactlyOneOrder() throws Exception {
        String key = "retry-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<OrderService.Placement>> attempts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            attempts.add(() -> orderService.place(CUSTOMER, key,
                    new PlaceOrderRequest(List.of(new PlaceOrderRequest.Item(IN_STOCK, 1)))));
        }

        List<Long> ids = new ArrayList<>();
        int created = 0;
        for (Future<OrderService.Placement> attempt : pool.invokeAll(attempts)) {
            OrderService.Placement placement = attempt.get(30, TimeUnit.SECONDS);
            ids.add(placement.order().id());
            created += placement.replayed() ? 0 : 1;
        }
        pool.shutdown();

        assertThat(ids).containsOnly(ids.get(0));
        assertThat(created).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE idempotency_key = ?", Integer.class, key))
                .isEqualTo(1);
    }

    @Test
    void customerCanCancelWhileTheSagaIsRunning() {
        OrderResponse placed = place(SLOW, 1);
        awaitStatus(placed.id(), OrderStatus.AWAITING_PAYMENT);

        orderService.cancel(placed.id(), CUSTOMER);

        OrderResponse finished = awaitStatus(placed.id(), OrderStatus.CANCELLED);
        assertThat(finished.cancellationCause()).isEqualTo(CancellationCause.CUSTOMER_REQUEST);
    }

    private OrderResponse place(long productId, int quantity) {
        return orderService.place(CUSTOMER, "it-" + UUID.randomUUID(),
                new PlaceOrderRequest(List.of(new PlaceOrderRequest.Item(productId, quantity)))).order();
    }

    private OrderResponse awaitStatus(Long orderId, OrderStatus expected) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> orderService.get(orderId, CUSTOMER).status() == expected);
        return orderService.get(orderId, CUSTOMER);
    }

    private static List<SagaStep> steps(OrderResponse order) {
        return order.timeline().stream().map(OrderResponse.Timeline::step).toList();
    }

    /** Stand-ins for product-service and payment-service that answer over Kafka. */
    @TestConfiguration
    static class FakeParticipantsConfig {

        @Bean
        FakeParticipants fakeParticipants(KafkaTemplate<String, String> kafka, MessageCodec codec) {
            return new FakeParticipants(kafka, codec);
        }
    }

    static class FakeParticipants {

        private final KafkaTemplate<String, String> kafka;
        private final MessageCodec codec;
        private final Map<Long, List<Message>> inventory = new ConcurrentHashMap<>();
        private final Map<Long, List<Message>> payments = new ConcurrentHashMap<>();
        private final Map<Long, BigDecimal> stalledCharges = new ConcurrentHashMap<>();

        FakeParticipants(KafkaTemplate<String, String> kafka, MessageCodec codec) {
            this.kafka = kafka;
            this.codec = codec;
        }

        @KafkaListener(topics = Topics.INVENTORY_COMMANDS, groupId = "fake-inventory")
        void inventory(ConsumerRecord<String, String> record) {
            OrderMessage command = (OrderMessage) codec.decode(record);
            inventory.computeIfAbsent(command.orderId(), id -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(command);
            if (command instanceof ReserveStockCommand reserve) {
                boolean available = reserve.lines().stream().noneMatch(line -> line.productId() == OUT_OF_STOCK);
                reply(available ? StockReserved.of(reserve.orderId())
                        : StockReservationFailed.of(reserve.orderId(), "Sold-out item has no stock"));
            } else if (command instanceof ReleaseStockCommand release) {
                reply(StockReleased.of(release.orderId(), true));
            }
        }

        @KafkaListener(topics = Topics.PAYMENT_COMMANDS, groupId = "fake-payments")
        void payments(ConsumerRecord<String, String> record) {
            OrderMessage command = (OrderMessage) codec.decode(record);
            payments.computeIfAbsent(command.orderId(), id -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(command);
            if (command instanceof ProcessPaymentCommand charge) {
                if (charge.amount().compareTo(new BigDecimal(SLOW_AMOUNT)) == 0) {
                    stalledCharges.put(charge.orderId(), charge.amount());
                } else if (charge.amount().compareTo(new BigDecimal(CARD_LIMIT)) > 0) {
                    reply(PaymentDeclined.of(charge.orderId(), "Amount exceeds the card limit"));
                } else {
                    reply(PaymentCompleted.of(charge.orderId(), "PAY-" + charge.orderId(), charge.amount()));
                }
            } else if (command instanceof RefundPaymentCommand refund) {
                BigDecimal stalled = stalledCharges.remove(refund.orderId());
                if (stalled != null) {
                    // The gateway finally answered the original charge — after the saga gave up on it.
                    reply(PaymentCompleted.of(refund.orderId(), "PAY-LATE-" + refund.orderId(), stalled));
                    reply(PaymentRefunded.of(refund.orderId(), stalled));
                } else {
                    reply(PaymentRefunded.of(refund.orderId(), BigDecimal.ZERO));
                }
            }
        }

        List<Message> inventoryCommandsFor(Long orderId) {
            return inventory.getOrDefault(orderId, List.of());
        }

        List<Message> paymentCommandsFor(Long orderId) {
            return payments.getOrDefault(orderId, List.of());
        }

        private void reply(OrderMessage reply) {
            ProducerRecord<String, String> record = new ProducerRecord<>(Topics.ORDER_SAGA_REPLIES,
                    String.valueOf(reply.orderId()), codec.encode(reply));
            record.headers().add(MessageHeaders.MESSAGE_ID, reply.messageId().toString().getBytes(StandardCharsets.UTF_8));
            record.headers().add(MessageHeaders.MESSAGE_TYPE, codec.typeOf(reply).getBytes(StandardCharsets.UTF_8));
            kafka.send(record);
        }
    }
}
