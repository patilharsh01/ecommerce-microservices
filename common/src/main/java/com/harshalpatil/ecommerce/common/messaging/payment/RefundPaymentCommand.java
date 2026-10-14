package com.harshalpatil.ecommerce.common.messaging.payment;

import com.harshalpatil.ecommerce.common.messaging.OrderMessage;

import java.time.Instant;
import java.util.UUID;

/**
 * Orchestrator → payments: compensating action for {@link ProcessPaymentCommand}, sent when
 * an order is cancelled while its payment may still be in flight. Refunds a completed
 * payment, or voids the order so that a late {@link ProcessPaymentCommand} is never charged.
 */
public record RefundPaymentCommand(UUID messageId, Long orderId, String reason, Instant occurredAt)
        implements OrderMessage {

    public static RefundPaymentCommand of(Long orderId, String reason) {
        return new RefundPaymentCommand(UUID.randomUUID(), orderId, reason, Instant.now());
    }
}
