package com.harshalpatil.ecommerce.payment.payments;

import com.harshalpatil.ecommerce.common.messaging.Topics;
import com.harshalpatil.ecommerce.common.messaging.payment.PaymentCompleted;
import com.harshalpatil.ecommerce.common.messaging.payment.PaymentDeclined;
import com.harshalpatil.ecommerce.common.messaging.payment.PaymentRefunded;
import com.harshalpatil.ecommerce.common.messaging.payment.ProcessPaymentCommand;
import com.harshalpatil.ecommerce.common.messaging.payment.RefundPaymentCommand;
import com.harshalpatil.ecommerce.payment.gateway.PaymentGateway;
import com.harshalpatil.ecommerce.payment.gateway.PaymentGateway.ChargeResult;
import com.harshalpatil.ecommerce.platform.messaging.OutboxWriter;
import com.harshalpatil.ecommerce.platform.web.AuthenticatedUser;
import com.harshalpatil.ecommerce.platform.web.NotFoundException;
import com.harshalpatil.ecommerce.platform.web.PageResponse;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Payments' side of the order saga.
 * <p>
 * Charging is split in two on purpose: the slow call to the card processor happens
 * <em>outside</em> any database transaction (no connection is held while the gateway thinks),
 * then the outcome, the reply and the inbox marker are committed together. If the service
 * dies in between, the redelivered command calls the gateway again with the same idempotency
 * key, which a real processor answers with the original result instead of charging twice.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final OutboxWriter outbox;
    private final MeterRegistry meterRegistry;

    /** @return false when this order already has a payment record (charged, declined or voided) */
    @Transactional(readOnly = true)
    public boolean needsCharge(Long orderId) {
        return !payments.existsByOrderId(orderId);
    }

    public ChargeResult charge(ProcessPaymentCommand command) {
        return gateway.charge(chargeKey(command.orderId()), command.customerId(), command.amount());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCharge(ProcessPaymentCommand command, ChargeResult result) {
        Long orderId = command.orderId();
        Optional<Payment> existing = payments.findByOrderId(orderId);
        if (existing.isPresent()) {
            log.info("Order {} already has a {} payment; repeating the outcome", orderId, existing.get().getStatus());
            replyWithOutcome(existing.get());
            return;
        }
        if (result == null) {
            throw new IllegalStateException("No gateway result for order " + orderId);
        }

        Payment payment = result.approved()
                ? Payment.completed(orderId, command.customerId(), command.amount(), result.reference())
                : Payment.declined(orderId, command.customerId(), command.amount(), result.declineReason());
        payments.save(payment);
        replyWithOutcome(payment);
        meterRegistry.counter("payments.charges", "outcome", result.approved() ? "approved" : "declined").increment();
        log.info("Payment for order {} {}", orderId, result.approved() ? "approved " + result.reference()
                : "declined: " + result.declineReason());
    }

    /**
     * Compensation. Refunds a completed charge. With no payment record, asks the gateway to void
     * anything charged under the order's key — the charge may have gone through right before we
     * failed to save its outcome — and records a VOIDED tombstone so a {@link ProcessPaymentCommand}
     * that arrives later is never charged. Both gateway calls carry idempotency keys, so a
     * redelivered command can't give money back twice.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refund(RefundPaymentCommand command) {
        Long orderId = command.orderId();
        Payment payment = payments.findByOrderId(orderId).orElse(null);

        if (payment == null) {
            BigDecimal voided = gateway.voidCharge(chargeKey(orderId), refundKey(orderId));
            payments.save(Payment.voided(orderId, voided, command.reason()));
            outbox.send(Topics.ORDER_SAGA_REPLIES, PaymentRefunded.of(orderId, voided));
            meterRegistry.counter("payments.refunds", "outcome", "voided").increment();
            log.info("Voided order {} ({} given back): {}", orderId, voided.toPlainString(), command.reason());
            return;
        }

        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            outbox.send(Topics.ORDER_SAGA_REPLIES, PaymentRefunded.of(orderId, BigDecimal.ZERO));
            log.info("Nothing to refund for order {} (payment is {})", orderId, payment.getStatus());
            return;
        }

        gateway.refund(payment.getReference(), payment.getAmount(), refundKey(orderId));
        payment.refund(command.reason());
        outbox.send(Topics.ORDER_SAGA_REPLIES, PaymentRefunded.of(orderId, payment.getAmount()));
        meterRegistry.counter("payments.refunds", "outcome", "refunded").increment();
        log.info("Refunded {} for order {}: {}", payment.getAmount(), orderId, command.reason());
    }

    @Transactional(readOnly = true)
    public PaymentResponse findByOrder(Long orderId, AuthenticatedUser user) {
        Payment payment = payments.findByOrderId(orderId)
                .filter(found -> user.isAdmin() || user.id().equals(found.getCustomerId()))
                // Someone else's payment answers exactly like a missing one: no existence leak.
                .orElseThrow(() -> new NotFoundException("Payment for order", orderId));
        return PaymentResponse.from(payment);
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> list(Pageable pageable) {
        return PageResponse.of(payments.findAll(pageable), PaymentResponse::from);
    }

    private void replyWithOutcome(Payment payment) {
        Long orderId = payment.getOrderId();
        switch (payment.getStatus()) {
            case COMPLETED -> outbox.send(Topics.ORDER_SAGA_REPLIES,
                    PaymentCompleted.of(orderId, payment.getReference(), payment.getAmount()));
            case DECLINED -> outbox.send(Topics.ORDER_SAGA_REPLIES, PaymentDeclined.of(orderId, payment.getFailureReason()));
            case VOIDED -> outbox.send(Topics.ORDER_SAGA_REPLIES,
                    PaymentDeclined.of(orderId, "Order was cancelled before it was charged"));
            case REFUNDED -> outbox.send(Topics.ORDER_SAGA_REPLIES,
                    PaymentDeclined.of(orderId, "Payment was already refunded"));
        }
    }

    private static String chargeKey(Long orderId) {
        return "order-" + orderId;
    }

    private static String refundKey(Long orderId) {
        return "refund-order-" + orderId;
    }
}
