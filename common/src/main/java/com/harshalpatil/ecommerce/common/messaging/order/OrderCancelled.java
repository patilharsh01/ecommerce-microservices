package com.harshalpatil.ecommerce.common.messaging.order;

import com.harshalpatil.ecommerce.common.messaging.OrderMessage;

import java.time.Instant;
import java.util.UUID;

/** Domain event: the saga ended without a sale and every compensation has run. */
public record OrderCancelled(UUID messageId, Long orderId, Long customerId, String reason, Instant occurredAt)
        implements OrderMessage {

    public static OrderCancelled of(Long orderId, Long customerId, String reason) {
        return new OrderCancelled(UUID.randomUUID(), orderId, customerId, reason, Instant.now());
    }
}
