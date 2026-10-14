package com.harshalpatil.ecommerce.order.domain;

import com.harshalpatil.ecommerce.platform.format.Rupees;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The order aggregate and the saga's state machine. Every transition checks the state it
 * starts from and appends to the timeline; the orchestrator decides which messages to send
 * based on what a transition returns. The {@code @Version} column serialises concurrent
 * writers (a reply listener racing the timeout sweeper): the loser rolls back and retries.
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    /** Total units ordered; denormalised so order lists don't have to load line items. */
    @Column(name = "item_count", nullable = false, updatable = false)
    private int itemCount;

    @Column(name = "idempotency_key", length = 100, updatable = false)
    private String idempotencyKey;

    /** Hash of the request body, so a reused idempotency key with a different body is caught. */
    @Column(name = "request_fingerprint", length = 64, updatable = false)
    private String requestFingerprint;

    @Column(name = "payment_reference", length = 64)
    private String paymentReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_cause", length = 30)
    private CancellationCause cancellationCause;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "awaiting_stock_release", nullable = false)
    private boolean awaitingStockRelease;

    @Column(name = "awaiting_refund", nullable = false)
    private boolean awaitingRefund;

    /**
     * When the current step times out (forward steps) or when unconfirmed compensations are
     * re-sent (COMPENSATING). Null once the order is CONFIRMED or CANCELLED.
     */
    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    @BatchSize(size = 50)
    private List<OrderLineItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    @OrderBy("id")
    @BatchSize(size = 50)
    private List<TimelineEntry> timeline = new ArrayList<>();

    public static Order place(Long customerId, List<OrderLineItem> items, String idempotencyKey,
                              String requestFingerprint, Duration stepTimeout) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one line item");
        }
        Order order = new Order();
        order.customerId = customerId;
        order.status = OrderStatus.PENDING;
        order.idempotencyKey = idempotencyKey;
        order.requestFingerprint = requestFingerprint;
        order.deadlineAt = Instant.now().plus(stepTimeout);
        items.forEach(order::addItem);
        order.totalAmount = items.stream().map(OrderLineItem::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        order.itemCount = items.stream().mapToInt(OrderLineItem::getQuantity).sum();
        order.record(SagaStep.ORDER_PLACED, order.itemCount + " item(s), " + Rupees.format(order.totalAmount));
        return order;
    }

    // ---- happy path -------------------------------------------------------------------------

    public void stockReserved(Duration stepTimeout) {
        requireStatus(OrderStatus.PENDING);
        status = OrderStatus.AWAITING_PAYMENT;
        deadlineAt = Instant.now().plus(stepTimeout);
        record(SagaStep.STOCK_RESERVED, "Stock held for every line");
    }

    public void confirm(String paymentReference, BigDecimal amountCharged) {
        requireStatus(OrderStatus.AWAITING_PAYMENT);
        this.paymentReference = paymentReference;
        record(SagaStep.PAYMENT_COMPLETED, "Charged " + Rupees.format(amountCharged) + " (" + paymentReference + ")");
        status = OrderStatus.CONFIRMED;
        complete();
        record(SagaStep.ORDER_CONFIRMED, null);
    }

    // ---- failure paths ----------------------------------------------------------------------

    /** Nothing was held, so there is nothing to undo: straight to CANCELLED. */
    public void stockRejected(String reason) {
        requireStatus(OrderStatus.PENDING);
        record(SagaStep.STOCK_REJECTED, reason);
        cancellationCause = CancellationCause.OUT_OF_STOCK;
        cancellationReason = reason;
        status = OrderStatus.CANCELLED;
        complete();
        record(SagaStep.ORDER_CANCELLED, reason);
    }

    public Compensation paymentDeclined(String reason, Duration retryAfter) {
        requireStatus(OrderStatus.AWAITING_PAYMENT);
        record(SagaStep.PAYMENT_DECLINED, reason);
        return startCompensation(CancellationCause.PAYMENT_DECLINED, "Payment declined: " + reason, retryAfter);
    }

    /**
     * Begins undoing an in-flight order. Stock is always released (a reservation may exist or
     * be about to). A refund is needed only if a payment may have been taken, i.e. the order
     * was already waiting on payment and the payment wasn't reported declined.
     */
    public Compensation startCompensation(CancellationCause cause, String reason, Duration retryAfter) {
        requireStatus(OrderStatus.PENDING, OrderStatus.AWAITING_PAYMENT);
        boolean refund = status == OrderStatus.AWAITING_PAYMENT && cause != CancellationCause.PAYMENT_DECLINED;
        awaitingStockRelease = true;
        awaitingRefund = refund;
        cancellationCause = cause;
        cancellationReason = reason;
        status = OrderStatus.COMPENSATING;
        deadlineAt = Instant.now().plus(retryAfter);
        record(SagaStep.COMPENSATION_STARTED, reason);
        return new Compensation(true, refund);
    }

    /**
     * A compensation wasn't confirmed in time; its command or reply may have been dead-lettered.
     * Returns what is still outstanding so it can be sent again. Participants answer a repeat
     * with "nothing to do", so re-sending is always safe.
     */
    public Compensation retryCompensation(Duration retryAfter) {
        requireStatus(OrderStatus.COMPENSATING);
        deadlineAt = Instant.now().plus(retryAfter);
        List<String> outstanding = new ArrayList<>();
        if (awaitingStockRelease) {
            outstanding.add("stock release");
        }
        if (awaitingRefund) {
            outstanding.add("refund");
        }
        record(SagaStep.COMPENSATION_RETRIED, "Still waiting for " + String.join(" and ", outstanding) + "; sent again");
        return new Compensation(awaitingStockRelease, awaitingRefund);
    }

    /** @return true when this was the last outstanding compensation and the order is now CANCELLED */
    public boolean stockReleased(boolean stockReturned) {
        requireStatus(OrderStatus.COMPENSATING);
        if (!awaitingStockRelease) {
            throw new IllegalStateException("Order " + id + " was not waiting for a stock release");
        }
        awaitingStockRelease = false;
        record(SagaStep.STOCK_RELEASED, stockReturned ? "Held stock returned to inventory" : "No stock was held");
        return finishIfCompensated();
    }

    /** @return true when this was the last outstanding compensation and the order is now CANCELLED */
    public boolean paymentRefunded(BigDecimal amountRefunded) {
        requireStatus(OrderStatus.COMPENSATING);
        if (!awaitingRefund) {
            throw new IllegalStateException("Order " + id + " was not waiting for a refund");
        }
        awaitingRefund = false;
        record(SagaStep.PAYMENT_REFUNDED, amountRefunded.signum() > 0
                ? "Refunded " + Rupees.format(amountRefunded)
                : "Nothing was charged; payment voided");
        return finishIfCompensated();
    }

    // ---- bookkeeping ------------------------------------------------------------------------

    public void recordCommand(SagaStep step, String detail) {
        if (step.kind() != SagaStep.Kind.COMMAND && step.kind() != SagaStep.Kind.COMPENSATION) {
            throw new IllegalArgumentException(step + " is not a command step");
        }
        record(step, detail);
    }

    /** A reply that no longer matters (the order moved on): keep it visible, change nothing. */
    public void ignoreLateReply(String replyType) {
        record(SagaStep.LATE_REPLY_IGNORED, replyType + " arrived while the order was " + status);
    }

    public boolean isTimedOut(Instant now) {
        return OrderStatus.WITH_DEADLINE.contains(status) && deadlineAt != null && deadlineAt.isBefore(now);
    }

    public List<OrderLineItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public List<TimelineEntry> getTimeline() {
        return Collections.unmodifiableList(timeline);
    }

    private boolean finishIfCompensated() {
        if (awaitingStockRelease || awaitingRefund) {
            return false;
        }
        status = OrderStatus.CANCELLED;
        complete();
        record(SagaStep.ORDER_CANCELLED, cancellationReason);
        return true;
    }

    private void complete() {
        completedAt = Instant.now();
        deadlineAt = null;
    }

    private void addItem(OrderLineItem item) {
        item.attachTo(this);
        items.add(item);
    }

    private void record(SagaStep step, String detail) {
        timeline.add(new TimelineEntry(this, step, detail));
    }

    private void requireStatus(OrderStatus... allowed) {
        if (!Arrays.asList(allowed).contains(status)) {
            throw new IllegalStateException("Order " + id + " is " + status + "; expected one of " + Arrays.toString(allowed));
        }
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Which undo commands a transition into {@link OrderStatus#COMPENSATING} requires. */
    public record Compensation(boolean releaseStock, boolean refundPayment) {
    }
}
