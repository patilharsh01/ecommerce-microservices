package com.harshalpatil.ecommerce.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Published by order-service the moment an order is placed.
 * Consumed by product-service (stock reservation) and notification-service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreatedEvent {
    private Long orderId;
    private Long customerId;
    private List<OrderItemDto> items;
    private BigDecimal totalAmount;
    private Instant createdAt;
}
