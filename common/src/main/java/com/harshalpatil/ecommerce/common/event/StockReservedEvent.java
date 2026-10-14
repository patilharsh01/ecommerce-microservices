package com.harshalpatil.ecommerce.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published by product-service after successfully reserving stock for an order.
 * Consumed by payment-service to trigger payment processing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockReservedEvent {
    private Long orderId;
    private Long customerId;
    private BigDecimal totalAmount;
    private Instant reservedAt;
}
