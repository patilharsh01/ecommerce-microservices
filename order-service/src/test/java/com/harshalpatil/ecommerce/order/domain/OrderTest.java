package com.harshalpatil.ecommerce.order.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void placingComputesTotalsAndStartsPendingWithADeadline() {
        Order order = newOrder();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("2500.00");
        assertThat(order.getItemCount()).isEqualTo(3);
        assertThat(order.getDeadlineAt()).isAfter(Instant.now());
        assertThat(steps(order)).containsExactly(SagaStep.ORDER_PLACED);
    }

    @Test
    void happyPathEndsConfirmedWithPaymentReference() {
        Order order = newOrder();

        order.stockReserved(TIMEOUT);
        order.confirm("PAY-1", new BigDecimal("2500.00"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaymentReference()).isEqualTo("PAY-1");
        assertThat(order.getCompletedAt()).isNotNull();
        assertThat(order.getDeadlineAt()).isNull();
        assertThat(steps(order)).containsExactly(SagaStep.ORDER_PLACED, SagaStep.STOCK_RESERVED,
                SagaStep.PAYMENT_COMPLETED, SagaStep.ORDER_CONFIRMED);
    }

    @Test
    void stockRejectionCancelsImmediatelyBecauseNothingIsHeld() {
        Order order = newOrder();

        order.stockRejected("Not enough stock");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancellationCause()).isEqualTo(CancellationCause.OUT_OF_STOCK);
    }

    @Test
    void declinedPaymentOnlyNeedsTheStockReleased() {
        Order order = newOrder();
        order.stockReserved(TIMEOUT);

        Order.Compensation compensation = order.paymentDeclined("Card declined", TIMEOUT);

        assertThat(compensation).isEqualTo(new Order.Compensation(true, false));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPENSATING);
        assertThat(order.stockReleased(true)).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancellationReason()).isEqualTo("Payment declined: Card declined");
    }

    @Test
    void timeoutWhileAwaitingPaymentWaitsForBothReleaseAndRefund() {
        Order order = newOrder();
        order.stockReserved(TIMEOUT);

        Order.Compensation compensation = order.startCompensation(CancellationCause.TIMED_OUT, "too slow", TIMEOUT);

        assertThat(compensation).isEqualTo(new Order.Compensation(true, true));
        assertThat(order.paymentRefunded(new BigDecimal("2500.00"))).as("stock not released yet").isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPENSATING);
        assertThat(order.stockReleased(true)).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancellingWhilePendingReleasesStockButNeverRefunds() {
        Order order = newOrder();

        assertThat(order.startCompensation(CancellationCause.CUSTOMER_REQUEST, "changed my mind", TIMEOUT))
                .isEqualTo(new Order.Compensation(true, false));
    }

    @Test
    void unconfirmedCompensationsHaveADeadlineAndOnlyTheOutstandingOnesAreRetried() {
        Order order = newOrder();
        order.stockReserved(TIMEOUT);
        order.startCompensation(CancellationCause.TIMED_OUT, "too slow", TIMEOUT);
        order.stockReleased(true);

        assertThat(order.isTimedOut(Instant.now().plus(Duration.ofMinutes(1)))).isTrue();
        Order.Compensation retry = order.retryCompensation(TIMEOUT);

        assertThat(retry).isEqualTo(new Order.Compensation(false, true));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPENSATING);
        assertThat(order.getDeadlineAt()).isAfter(Instant.now());
        assertThat(steps(order)).last().isEqualTo(SagaStep.COMPENSATION_RETRIED);
        assertThat(order.paymentRefunded(BigDecimal.TEN)).isTrue();
        assertThat(order.getDeadlineAt()).isNull();
    }

    @Test
    void transitionsFromTheWrongStateAreRejected() {
        Order order = newOrder();

        assertThatThrownBy(() -> order.confirm("PAY-1", BigDecimal.TEN)).isInstanceOf(IllegalStateException.class);
        order.stockRejected("none left");
        assertThatThrownBy(() -> order.startCompensation(CancellationCause.CUSTOMER_REQUEST, "late", TIMEOUT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> order.stockReleased(true)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyInFlightOrdersPastTheirDeadlineAreTimedOut() {
        Order order = newOrder();

        assertThat(order.isTimedOut(Instant.now())).isFalse();
        assertThat(order.isTimedOut(Instant.now().plus(Duration.ofMinutes(1)))).isTrue();
        order.stockRejected("none left");
        assertThat(order.isTimedOut(Instant.now().plus(Duration.ofMinutes(1)))).isFalse();
    }

    private static Order newOrder() {
        return Order.place(7L, List.of(
                new OrderLineItem(1L, "Keyboard", 2, new BigDecimal("1000.00")),
                new OrderLineItem(2L, "Mouse pad", 1, new BigDecimal("500.00"))), "key-123456", "fp", TIMEOUT);
    }

    private static List<SagaStep> steps(Order order) {
        return order.getTimeline().stream().map(TimelineEntry::getStep).toList();
    }
}
