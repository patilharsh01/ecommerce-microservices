package com.harshalpatil.ecommerce.common.messaging.payment;

import com.harshalpatil.ecommerce.common.messaging.OrderMessage;

import java.time.Instant;
import java.util.UUID;

public record PaymentDeclined(UUID messageId, Long orderId, String reason, Instant occurredAt)
        implements OrderMessage {

    public static PaymentDeclined of(Long orderId, String reason) {
        return new PaymentDeclined(UUID.randomUUID(), orderId, reason, Instant.now());
    }
}
