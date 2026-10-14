package com.harshalpatil.ecommerce.payment.payments;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(Long orderId, Long customerId, BigDecimal amount, PaymentStatus status, String reference,
                              String failureReason, String refundReason, Instant createdAt, Instant updatedAt) {

    static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getOrderId(), payment.getCustomerId(), payment.getAmount(),
                payment.getStatus(), payment.getReference(), payment.getFailureReason(), payment.getRefundReason(),
                payment.getCreatedAt(), payment.getUpdatedAt());
    }
}
