package com.harshalpatil.ecommerce.payment.payments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/** At most one per order (unique order_id), which is what makes charging idempotent. */
@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true, updatable = false)
    private Long orderId;

    /** Null only for {@link PaymentStatus#VOIDED} tombstones, which are created without a charge request. */
    @Column(name = "customer_id")
    private Long customerId;

    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(length = 64)
    private String reference;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "refund_reason", length = 500)
    private String refundReason;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private Payment(Long orderId, Long customerId, BigDecimal amount, PaymentStatus status, String reference,
                    String failureReason) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.amount = amount;
        this.status = status;
        this.reference = reference;
        this.failureReason = failureReason;
    }

    static Payment completed(Long orderId, Long customerId, BigDecimal amount, String reference) {
        return new Payment(orderId, customerId, amount, PaymentStatus.COMPLETED, reference, null);
    }

    static Payment declined(Long orderId, Long customerId, BigDecimal amount, String reason) {
        return new Payment(orderId, customerId, amount, PaymentStatus.DECLINED, null, reason);
    }

    /** @param amountReturned what the gateway gave back for a charge we had no record of (usually zero) */
    static Payment voided(Long orderId, BigDecimal amountReturned, String reason) {
        Payment payment = new Payment(orderId, null, amountReturned.signum() > 0 ? amountReturned : null,
                PaymentStatus.VOIDED, null, null);
        payment.refundReason = reason;
        return payment;
    }

    void refund(String reason) {
        if (status != PaymentStatus.COMPLETED) {
            throw new IllegalStateException("Only completed payments can be refunded; payment for order "
                    + orderId + " is " + status);
        }
        this.status = PaymentStatus.REFUNDED;
        this.refundReason = reason;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
