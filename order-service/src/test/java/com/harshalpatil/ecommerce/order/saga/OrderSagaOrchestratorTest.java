package com.harshalpatil.ecommerce.order.saga;

import com.harshalpatil.ecommerce.common.messaging.OrderMessage;
import com.harshalpatil.ecommerce.common.messaging.Topics;
import com.harshalpatil.ecommerce.common.messaging.inventory.ReleaseStockCommand;
import com.harshalpatil.ecommerce.common.messaging.inventory.ReserveStockCommand;
import com.harshalpatil.ecommerce.common.messaging.inventory.StockReleased;
import com.harshalpatil.ecommerce.common.messaging.inventory.StockReservationFailed;
import com.harshalpatil.ecommerce.common.messaging.inventory.StockReserved;
import com.harshalpatil.ecommerce.common.messaging.order.OrderCancelled;
import com.harshalpatil.ecommerce.common.messaging.order.OrderConfirmed;
import com.harshalpatil.ecommerce.common.messaging.order.OrderPlaced;
import com.harshalpatil.ecommerce.common.messaging.payment.PaymentCompleted;
import com.harshalpatil.ecommerce.common.messaging.payment.PaymentDeclined;
import com.harshalpatil.ecommerce.common.messaging.payment.PaymentRefunded;
import com.harshalpatil.ecommerce.common.messaging.payment.ProcessPaymentCommand;
import com.harshalpatil.ecommerce.common.messaging.payment.RefundPaymentCommand;
import com.harshalpatil.ecommerce.order.domain.CancellationCause;
import com.harshalpatil.ecommerce.order.domain.Order;
import com.harshalpatil.ecommerce.order.domain.OrderLineItem;
import com.harshalpatil.ecommerce.order.domain.OrderRepository;
import com.harshalpatil.ecommerce.order.domain.OrderStatus;
import com.harshalpatil.ecommerce.order.domain.SagaStep;
import com.harshalpatil.ecommerce.order.domain.TimelineEntry;
import com.harshalpatil.ecommerce.platform.messaging.OutboxWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Every branch of the saga, with persistence and Kafka mocked out. */
class OrderSagaOrchestratorTest {

    private static final long ORDER_ID = 42L;

    private final OrderRepository orders = mock(OrderRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private OrderSagaOrchestrator orchestrator;
    private Order order;

    @BeforeEach
    void setUp() {
        orchestrator = new OrderSagaOrchestrator(orders, outbox, new SagaProperties(Duration.ofSeconds(30),
                Duration.ofSeconds(5), 50), new SimpleMeterRegistry());
        order = Order.place(7L, List.of(new OrderLineItem(1L, "Keyboard", 2, new BigDecimal("1000.00"))),
                null, "fp", Duration.ofSeconds(30));
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        ReflectionTestUtils.setField(order, "createdAt", Instant.now());
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
    }

    @Test
    void startAsksInventoryToReserveAndAnnouncesThePlacement() {
        orchestrator.start(order);

        assertThat(sent()).extracting(m -> (Class) m.getClass())
                .containsExactly(ReserveStockCommand.class, OrderPlaced.class);
        ReserveStockCommand reserve = (ReserveStockCommand) sent().get(0);
        assertThat(reserve.lines()).singleElement().satisfies(line -> {
            assertThat(line.productId()).isEqualTo(1L);
            assertThat(line.quantity()).isEqualTo(2);
        });
    }

    @Test
    void stockReservedLeadsToAPaymentRequestForTheOrderTotal() {
        orchestrator.onReply(StockReserved.of(ORDER_ID));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(sent()).singleElement().isInstanceOfSatisfying(ProcessPaymentCommand.class, command -> {
            assertThat(command.amount()).isEqualByComparingTo("2000.00");
            assertThat(command.customerId()).isEqualTo(7L);
        });
        verifySentTo(Topics.PAYMENT_COMMANDS);
    }

    @Test
    void paymentCompletedConfirmsTheOrder() {
        orchestrator.onReply(StockReserved.of(ORDER_ID));
        clearInvocations(outbox);

        orchestrator.onReply(PaymentCompleted.of(ORDER_ID, "PAY-9", new BigDecimal("2000.00")));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(sent()).singleElement().isInstanceOf(OrderConfirmed.class);
        verifySentTo(Topics.ORDER_EVENTS);
    }

    @Test
    void outOfStockCancelsWithoutAnyCompensation() {
        orchestrator.onReply(StockReservationFailed.of(ORDER_ID, "Not enough stock"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(sent()).singleElement().isInstanceOf(OrderCancelled.class);
    }

    @Test
    void declinedPaymentReleasesStockThenCancels() {
        orchestrator.onReply(StockReserved.of(ORDER_ID));
        clearInvocations(outbox);

        orchestrator.onReply(PaymentDeclined.of(ORDER_ID, "Card declined"));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPENSATING);
        assertThat(sent()).singleElement().isInstanceOf(ReleaseStockCommand.class);
        clearInvocations(outbox);

        orchestrator.onReply(StockReleased.of(ORDER_ID, true));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(sent()).singleElement().isInstanceOf(OrderCancelled.class);
    }

    @Test
    void timeoutDuringPaymentReleasesAndRefundsAndIgnoresTheLateCharge() {
        orchestrator.onReply(StockReserved.of(ORDER_ID));
        clearInvocations(outbox);

        orchestrator.cancel(order, CancellationCause.TIMED_OUT, "No answer within 30s");
        assertThat(sent()).extracting(m -> (Class) m.getClass())
                .containsExactly(ReleaseStockCommand.class, RefundPaymentCommand.class);
        clearInvocations(outbox);

        // The slow gateway finally answers: too late, the refund is already queued behind it.
        orchestrator.onReply(PaymentCompleted.of(ORDER_ID, "PAY-LATE", new BigDecimal("2000.00")));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPENSATING);
        verify(outbox, never()).send(anyString(), any(OrderMessage.class));

        orchestrator.onReply(StockReleased.of(ORDER_ID, true));
        assertThat(order.getStatus()).as("refund still outstanding").isEqualTo(OrderStatus.COMPENSATING);
        orchestrator.onReply(PaymentRefunded.of(ORDER_ID, new BigDecimal("2000.00")));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancellationCause()).isEqualTo(CancellationCause.TIMED_OUT);
        assertThat(steps()).contains(SagaStep.LATE_REPLY_IGNORED, SagaStep.PAYMENT_REFUNDED, SagaStep.ORDER_CANCELLED);
    }

    @Test
    void lateStockReservedAfterACancelIsIgnoredBecauseTheReleaseIsQueuedBehindIt() {
        orchestrator.cancel(order, CancellationCause.CUSTOMER_REQUEST, "Cancelled by the customer");
        clearInvocations(outbox);

        orchestrator.onReply(StockReserved.of(ORDER_ID));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPENSATING);
        verify(outbox, never()).send(anyString(), any(OrderMessage.class));
        assertThat(steps()).last().isEqualTo(SagaStep.LATE_REPLY_IGNORED);
    }

    @Test
    void anUnconfirmedRefundIsSentAgainButTheConfirmedReleaseIsNot() {
        orchestrator.onReply(StockReserved.of(ORDER_ID));
        orchestrator.cancel(order, CancellationCause.TIMED_OUT, "No answer within 30s");
        orchestrator.onReply(StockReleased.of(ORDER_ID, true));
        clearInvocations(outbox);

        // The PaymentRefunded reply was dead-lettered; the sweeper finds the order still compensating.
        orchestrator.retryCompensation(order);

        assertThat(sent()).singleElement().isInstanceOf(RefundPaymentCommand.class);
        orchestrator.onReply(PaymentRefunded.of(ORDER_ID, new BigDecimal("2000.00")));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void repliesForUnknownOrdersAreDroppedQuietly() {
        when(orders.findById(999L)).thenReturn(Optional.empty());

        orchestrator.onReply(StockReserved.of(999L));

        verify(outbox, never()).send(anyString(), any(OrderMessage.class));
    }

    private List<OrderMessage> sent() {
        ArgumentCaptor<OrderMessage> captor = ArgumentCaptor.forClass(OrderMessage.class);
        verify(outbox, org.mockito.Mockito.atLeast(0)).send(anyString(), captor.capture());
        return captor.getAllValues();
    }

    private void verifySentTo(String topic) {
        verify(outbox).send(org.mockito.ArgumentMatchers.eq(topic), any(OrderMessage.class));
    }

    private List<SagaStep> steps() {
        return order.getTimeline().stream().map(TimelineEntry::getStep).toList();
    }
}
