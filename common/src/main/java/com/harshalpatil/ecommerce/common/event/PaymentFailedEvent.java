package com.harshalpatil.ecommerce.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Published by payment-service when payment processing fails.
 * Consumed by order-service (mark CANCELLED, triggers stock release) and notification-service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentFailedEvent {
    private Long orderId;
    private Long customerId;
    private String reason;
    private Instant failedAt;
}
