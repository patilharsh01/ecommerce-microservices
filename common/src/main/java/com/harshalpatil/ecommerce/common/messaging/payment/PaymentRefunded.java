package com.harshalpatil.ecommerce.common.messaging.payment;

import com.harshalpatil.ecommerce.common.messaging.OrderMessage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Answer to {@link RefundPaymentCommand}. {@code amountRefunded} is zero when there was
 * nothing to refund (the payment was declined, or never attempted and is now voided).
 */
public record PaymentRefunded(UUID messageId, Long orderId, BigDecimal amountRefunded, Instant occurredAt)
        implements OrderMessage {

    public static PaymentRefunded of(Long orderId, BigDecimal amountRefunded) {
        return new PaymentRefunded(UUID.randomUUID(), orderId, amountRefunded, Instant.now());
    }
}
