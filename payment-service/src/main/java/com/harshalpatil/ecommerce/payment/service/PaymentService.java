package com.harshalpatil.ecommerce.payment.service;

import com.harshalpatil.ecommerce.common.event.PaymentCompletedEvent;
import com.harshalpatil.ecommerce.common.event.PaymentFailedEvent;
import com.harshalpatil.ecommerce.common.event.StockReservedEvent;
import com.harshalpatil.ecommerce.common.kafka.KafkaTopics;
import com.harshalpatil.ecommerce.payment.dto.PaymentResponse;
import com.harshalpatil.ecommerce.payment.entity.Payment;
import com.harshalpatil.ecommerce.payment.entity.PaymentStatus;
import com.harshalpatil.ecommerce.payment.exception.PaymentNotFoundException;
import com.harshalpatil.ecommerce.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Stands in for a real gateway (Razorpay/Stripe). Orders above the configured
 * limit are declined to make the failure branch of the saga demonstrable —
 * a real integration would swap this method body for an actual API call.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${payment.simulation.max-approved-amount:50000}")
    private BigDecimal maxApprovedAmount;

    @Transactional
    public void process(StockReservedEvent event) {
        boolean approved = event.getTotalAmount().compareTo(maxApprovedAmount) <= 0;

        Payment payment = Payment.builder()
                .orderId(event.getOrderId())
                .customerId(event.getCustomerId())
                .amount(event.getTotalAmount())
                .status(approved ? PaymentStatus.COMPLETED : PaymentStatus.FAILED)
                .reference(approved ? "PAY-" + UUID.randomUUID() : null)
                .failureReason(approved ? null : "Amount exceeds approved transaction limit of " + maxApprovedAmount)
                .build();

        payment = paymentRepository.save(payment);

        if (approved) {
            kafkaTemplate.send(KafkaTopics.PAYMENT_COMPLETED, String.valueOf(event.getOrderId()),
                    PaymentCompletedEvent.builder()
                            .orderId(event.getOrderId())
                            .customerId(event.getCustomerId())
                            .paymentReference(payment.getReference())
                            .amount(event.getTotalAmount())
                            .completedAt(Instant.now())
                            .build());
            log.info("Payment completed for order {}", event.getOrderId());
        } else {
            kafkaTemplate.send(KafkaTopics.PAYMENT_FAILED, String.valueOf(event.getOrderId()),
                    PaymentFailedEvent.builder()
                            .orderId(event.getOrderId())
                            .customerId(event.getCustomerId())
                            .reason(payment.getFailureReason())
                            .failedAt(Instant.now())
                            .build());
            log.warn("Payment failed for order {}: {}", event.getOrderId(), payment.getFailureReason());
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse findByOrderId(Long orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new PaymentNotFoundException(orderId));

        return PaymentResponse.builder()
                .orderId(payment.getOrderId())
                .amount(payment.getAmount())
                .status(payment.getStatus())
                .reference(payment.getReference())
                .failureReason(payment.getFailureReason())
                .createdAt(payment.getCreatedAt())
                .build();
    }
}
