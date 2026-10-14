package com.harshalpatil.ecommerce.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Published by product-service when an order cannot be fulfilled (insufficient stock).
 * Consumed by order-service to cancel the order, and by notification-service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockRejectedEvent {
    private Long orderId;
    private Long customerId;
    private String reason;
    private Instant rejectedAt;
}
