package com.harshalpatil.ecommerce.common.messaging.inventory;

import com.harshalpatil.ecommerce.common.messaging.OrderMessage;

import java.time.Instant;
import java.util.UUID;

public record StockReserved(UUID messageId, Long orderId, Instant occurredAt) implements OrderMessage {

    public static StockReserved of(Long orderId) {
        return new StockReserved(UUID.randomUUID(), orderId, Instant.now());
    }
}
