package com.harshalpatil.ecommerce.common.messaging.order;

import com.harshalpatil.ecommerce.common.messaging.OrderMessage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Domain event: a customer placed an order. Published on {@code order.events}. */
public record OrderPlaced(UUID messageId, Long orderId, Long customerId, BigDecimal totalAmount, int itemCount,
                          Instant occurredAt) implements OrderMessage {

    public static OrderPlaced of(Long orderId, Long customerId, BigDecimal totalAmount, int itemCount) {
        return new OrderPlaced(UUID.randomUUID(), orderId, customerId, totalAmount, itemCount, Instant.now());
    }
}
