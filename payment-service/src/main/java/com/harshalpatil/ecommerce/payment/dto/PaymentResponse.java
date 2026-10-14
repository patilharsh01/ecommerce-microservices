package com.harshalpatil.ecommerce.payment.dto;

import com.harshalpatil.ecommerce.payment.entity.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {
    private Long orderId;
    private BigDecimal amount;
    private PaymentStatus status;
    private String reference;
    private String failureReason;
    private Instant createdAt;
}
