package com.harshalpatil.ecommerce.order.saga;

import com.harshalpatil.ecommerce.common.messaging.OrderMessage;
import com.harshalpatil.ecommerce.common.messaging.Topics;
import com.harshalpatil.ecommerce.common.messaging.inventory.OrderLine;
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
import com.harshalpatil.ecommerce.order.domain.OrderRepository;
import com.harshalpatil.ecommerce.order.domain.OrderStatus;
import com.harshalpatil.ecommerce.order.domain.SagaStep;
import com.harshalpatil.ecommerce.platform.format.Rupees;
import com.harshalpatil.ecommerce.platform.messaging.OutboxWriter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * Drives the order saga: decides the next command from the order's state and the reply that
 * just arrived, and records every decision on the order's timeline.
 * <pre>
 *   placed ─▶ ReserveStock ─┬─ StockReserved ─▶ ProcessPayment ─┬─ PaymentCompleted ─▶ CONFIRMED
 *                           │                                   └─ PaymentDeclined ─▶ ReleaseStock ─▶ CANCELLED
 *                           └─ StockReservationFailed ─▶ CANCELLED
 *   timeout / customer cancel ─▶ ReleaseStock (+ RefundPayment if payment was requested) ─▶ CANCELLED
 *   compensation not confirmed in time ─▶ the outstanding compensations are sent again
 * </pre>
 * Every method runs inside the caller's transaction (inbox, HTTP request or sweeper), so the
 * state change and the commands it triggers are committed atomically through the outbox.
 * <p>
 * A reply that no longer fits the order's state (it was cancelled meanwhile, or a participant
 * repeated itself) is recorded and ignored. Compensations are always queued <em>behind</em> the
 * command they undo on the same partition, so ignoring the late reply is safe.
 */
@Service
@Slf4j
@Transactional(propagation = Propagation.MANDATORY)
public class OrderSagaOrchestrator {

    private final OrderRepository orders;
    private final OutboxWriter outbox;
    private final SagaProperties properties;
    private final MeterRegistry meterRegistry;

    public OrderSagaOrchestrator(OrderRepository orders, OutboxWriter outbox, SagaProperties properties,
                                 MeterRegistry meterRegistry) {
        this.orders = orders;
        this.outbox = outbox;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /** First step, called right after the order row is inserted. */
    public void start(Order order) {
        List<OrderLine> lines = order.getItems().stream()
                .map(item -> new OrderLine(item.getProductId(), item.getQuantity()))
                .toList();
        outbox.send(Topics.INVENTORY_COMMANDS, ReserveStockCommand.of(order.getId(), lines));
        order.recordCommand(SagaStep.RESERVE_STOCK_SENT, "Hold " + order.getItemCount() + " unit(s)");
        outbox.send(Topics.ORDER_EVENTS, OrderPlaced.of(order.getId(), order.getCustomerId(), order.getTotalAmount(),
                order.getItemCount()));
        meterRegistry.counter("orders.placed").increment();
    }

    public void onReply(OrderMessage reply) {
        Order order = orders.findById(reply.orderId()).orElse(null);
        if (order == null) {
            log.warn("Reply {} refers to unknown order {}; ignoring", reply.getClass().getSimpleName(), reply.orderId());
            return;
        }

        if (reply instanceof StockReserved) {
            onStockReserved(order);
        } else if (reply instanceof StockReservationFailed failed) {
            onStockReservationFailed(order, failed);
        } else if (reply instanceof PaymentCompleted completed) {
            onPaymentCompleted(order, completed);
        } else if (reply instanceof PaymentDeclined declined) {
            onPaymentDeclined(order, declined);
        } else if (reply instanceof StockReleased released) {
            onStockReleased(order, released);
        } else if (reply instanceof PaymentRefunded refunded) {
            onPaymentRefunded(order, refunded);
        } else {
            log.warn("Unsupported reply {} for order {}", reply.getClass().getSimpleName(), order.getId());
        }
    }

    /** Cancels an in-flight order because its step overran, or because the customer asked. */
    public void cancel(Order order, CancellationCause cause, String reason) {
        compensate(order, order.startCompensation(cause, reason, properties.stepTimeout()));
    }

    /** Sends again the compensations a participant hasn't confirmed yet. */
    public void retryCompensation(Order order) {
        compensate(order, order.retryCompensation(properties.stepTimeout()));
        meterRegistry.counter("order.saga.compensation.retries").increment();
    }

    // ---- reply handlers ---------------------------------------------------------------------

    private void onStockReserved(Order order) {
        if (order.getStatus() != OrderStatus.PENDING) {
            ignore(order, "StockReserved");
            return;
        }
        order.stockReserved(properties.stepTimeout());
        outbox.send(Topics.PAYMENT_COMMANDS,
                ProcessPaymentCommand.of(order.getId(), order.getCustomerId(), order.getTotalAmount()));
        order.recordCommand(SagaStep.PROCESS_PAYMENT_SENT, "Charge " + Rupees.format(order.getTotalAmount()));
    }

    private void onStockReservationFailed(Order order, StockReservationFailed reply) {
        if (order.getStatus() != OrderStatus.PENDING) {
            ignore(order, "StockReservationFailed");
            return;
        }
        order.stockRejected(reply.reason());
        announceCancelled(order);
    }

    private void onPaymentCompleted(Order order, PaymentCompleted reply) {
        if (order.getStatus() != OrderStatus.AWAITING_PAYMENT) {
            ignore(order, "PaymentCompleted");
            return;
        }
        order.confirm(reply.paymentReference(), reply.amount());
        outbox.send(Topics.ORDER_EVENTS, OrderConfirmed.of(order.getId(), order.getCustomerId(), order.getTotalAmount(),
                reply.paymentReference()));
        recordOutcome(order, "confirmed");
    }

    private void onPaymentDeclined(Order order, PaymentDeclined reply) {
        if (order.getStatus() != OrderStatus.AWAITING_PAYMENT) {
            ignore(order, "PaymentDeclined");
            return;
        }
        compensate(order, order.paymentDeclined(reply.reason(), properties.stepTimeout()));
    }

    private void onStockReleased(Order order, StockReleased reply) {
        if (order.getStatus() != OrderStatus.COMPENSATING || !order.isAwaitingStockRelease()) {
            ignore(order, "StockReleased");
            return;
        }
        if (order.stockReleased(reply.stockReturned())) {
            announceCancelled(order);
        }
    }

    private void onPaymentRefunded(Order order, PaymentRefunded reply) {
        if (order.getStatus() != OrderStatus.COMPENSATING || !order.isAwaitingRefund()) {
            ignore(order, "PaymentRefunded");
            return;
        }
        if (order.paymentRefunded(reply.amountRefunded())) {
            announceCancelled(order);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private void compensate(Order order, Order.Compensation compensation) {
        if (compensation.releaseStock()) {
            outbox.send(Topics.INVENTORY_COMMANDS, ReleaseStockCommand.of(order.getId(), order.getCancellationReason()));
            order.recordCommand(SagaStep.RELEASE_STOCK_SENT, "Release any stock held for the order");
        }
        if (compensation.refundPayment()) {
            outbox.send(Topics.PAYMENT_COMMANDS, RefundPaymentCommand.of(order.getId(), order.getCancellationReason()));
            order.recordCommand(SagaStep.REFUND_PAYMENT_SENT, "Refund or void the payment");
        }
        log.info("Compensating order {} ({}): {}", order.getId(), order.getCancellationCause(), order.getCancellationReason());
    }

    private void announceCancelled(Order order) {
        outbox.send(Topics.ORDER_EVENTS, OrderCancelled.of(order.getId(), order.getCustomerId(), order.getCancellationReason()));
        recordOutcome(order, "cancelled");
    }

    private void ignore(Order order, String replyType) {
        order.ignoreLateReply(replyType);
        meterRegistry.counter("order.saga.late.replies", "type", replyType).increment();
        log.info("Ignoring {} for order {} in status {}", replyType, order.getId(), order.getStatus());
    }

    private void recordOutcome(Order order, String outcome) {
        String cause = order.getCancellationCause() == null ? "none" : order.getCancellationCause().name().toLowerCase();
        meterRegistry.counter("orders.completed", "outcome", outcome, "cause", cause).increment();
        Timer.builder("order.saga.duration")
                .description("Time from order placement until the saga reached CONFIRMED or CANCELLED")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(Duration.between(order.getCreatedAt(), order.getCompletedAt()));
        log.info("Order {} {} after {}", order.getId(), outcome, Duration.between(order.getCreatedAt(), order.getCompletedAt()));
    }
}
