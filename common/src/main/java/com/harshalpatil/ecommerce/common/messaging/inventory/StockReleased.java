package com.harshalpatil.ecommerce.common.messaging.inventory;

import com.harshalpatil.ecommerce.common.messaging.OrderMessage;

import java.time.Instant;
import java.util.UUID;

/**
 * Inventory no longer holds a reservation for the order. Sent whether stock was actually
 * returned or there was nothing to return, so the orchestrator can always finish its
 * compensation.
 */
public record StockReleased(UUID messageId, Long orderId, boolean stockReturned, Instant occurredAt)
        implements OrderMessage {

    public static StockReleased of(Long orderId, boolean stockReturned) {
        return new StockReleased(UUID.randomUUID(), orderId, stockReturned, Instant.now());
    }
}
