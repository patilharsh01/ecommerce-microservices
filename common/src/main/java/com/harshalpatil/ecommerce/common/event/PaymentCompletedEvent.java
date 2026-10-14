package com.harshalpatil.ecommerce.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published by payment-service once a payment is successfully processed.
 * Consumed by order-service (mark CONFIRMED) and notification-service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCompletedEvent {
    private Long orderId;
    private Long customerId;
    private String paymentReference;
    private BigDecimal amount;
    private Instant completedAt;
}
