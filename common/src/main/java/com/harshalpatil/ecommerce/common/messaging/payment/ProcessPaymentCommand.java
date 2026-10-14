package com.harshalpatil.ecommerce.common.messaging.payment;

import com.harshalpatil.ecommerce.common.messaging.OrderMessage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Orchestrator → payments: charge the customer for an order whose stock is already held. */
public record ProcessPaymentCommand(UUID messageId, Long orderId, Long customerId, BigDecimal amount,
                                    Instant occurredAt) implements OrderMessage {

    public static ProcessPaymentCommand of(Long orderId, Long customerId, BigDecimal amount) {
        return new ProcessPaymentCommand(UUID.randomUUID(), orderId, customerId, amount, Instant.now());
    }
}
