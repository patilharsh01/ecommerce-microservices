package com.harshalpatil.ecommerce.common.messaging.order;

import com.harshalpatil.ecommerce.common.messaging.OrderMessage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Domain event: the saga finished successfully — stock is held and payment captured. */
public record OrderConfirmed(UUID messageId, Long orderId, Long customerId, BigDecimal totalAmount,
                             String paymentReference, Instant occurredAt) implements OrderMessage {

    public static OrderConfirmed of(Long orderId, Long customerId, BigDecimal totalAmount, String paymentReference) {
        return new OrderConfirmed(UUID.randomUUID(), orderId, customerId, totalAmount, paymentReference, Instant.now());
    }
}
