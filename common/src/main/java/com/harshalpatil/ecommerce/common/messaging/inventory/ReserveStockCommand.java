package com.harshalpatil.ecommerce.common.messaging.inventory;

import com.harshalpatil.ecommerce.common.messaging.OrderMessage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Orchestrator → inventory: hold stock for every line of the order, all or nothing. */
public record ReserveStockCommand(UUID messageId, Long orderId, List<OrderLine> lines, Instant occurredAt)
        implements OrderMessage {

    public static ReserveStockCommand of(Long orderId, List<OrderLine> lines) {
        return new ReserveStockCommand(UUID.randomUUID(), orderId, List.copyOf(lines), Instant.now());
    }
}
