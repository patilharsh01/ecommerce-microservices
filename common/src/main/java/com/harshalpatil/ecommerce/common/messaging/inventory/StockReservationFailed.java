package com.harshalpatil.ecommerce.common.messaging.inventory;

import com.harshalpatil.ecommerce.common.messaging.OrderMessage;

import java.time.Instant;
import java.util.UUID;

public record StockReservationFailed(UUID messageId, Long orderId, String reason, Instant occurredAt)
        implements OrderMessage {

    public static StockReservationFailed of(Long orderId, String reason) {
        return new StockReservationFailed(UUID.randomUUID(), orderId, reason, Instant.now());
    }
}
