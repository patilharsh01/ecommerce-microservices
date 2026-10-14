package com.harshalpatil.ecommerce.common.messaging.payment;

import com.harshalpatil.ecommerce.common.messaging.OrderMessage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentCompleted(UUID messageId, Long orderId, String paymentReference, BigDecimal amount,
                               Instant occurredAt) implements OrderMessage {

    public static PaymentCompleted of(Long orderId, String paymentReference, BigDecimal amount) {
        return new PaymentCompleted(UUID.randomUUID(), orderId, paymentReference, amount, Instant.now());
    }
}
