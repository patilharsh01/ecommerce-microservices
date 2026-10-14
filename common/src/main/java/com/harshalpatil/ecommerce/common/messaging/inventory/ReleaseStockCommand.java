package com.harshalpatil.ecommerce.common.messaging.inventory;

import com.harshalpatil.ecommerce.common.messaging.OrderMessage;

import java.time.Instant;
import java.util.UUID;

/**
 * Orchestrator → inventory: compensating action for {@link ReserveStockCommand}.
 * Must be safe to receive even if the reservation never happened (or hasn't happened yet).
 */
public record ReleaseStockCommand(UUID messageId, Long orderId, String reason, Instant occurredAt)
        implements OrderMessage {

    public static ReleaseStockCommand of(Long orderId, String reason) {
        return new ReleaseStockCommand(UUID.randomUUID(), orderId, reason, Instant.now());
    }
}
