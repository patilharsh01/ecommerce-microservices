package com.harshalpatil.ecommerce.product.messaging;

import com.harshalpatil.ecommerce.common.event.OrderCreatedEvent;
import com.harshalpatil.ecommerce.common.event.StockRejectedEvent;
import com.harshalpatil.ecommerce.common.event.StockReservedEvent;
import com.harshalpatil.ecommerce.common.kafka.KafkaTopics;
import com.harshalpatil.ecommerce.product.exception.InsufficientStockException;
import com.harshalpatil.ecommerce.product.service.StockReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * First hop of the order saga: order-service publishes OrderCreated, this
 * listener attempts to reserve stock for every line item and reports back
 * with StockReserved (happy path) or StockRejected (out of stock), which
 * order-service and notification-service both react to.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventsListener {

    private final StockReservationService stockReservationService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(topics = KafkaTopics.ORDER_CREATED, groupId = "product-service")
    public void onOrderCreated(OrderCreatedEvent event) {
        log.info("Received OrderCreatedEvent for order {}", event.getOrderId());
        try {
            stockReservationService.reserve(event.getItems());

            StockReservedEvent reserved = StockReservedEvent.builder()
                    .orderId(event.getOrderId())
                    .customerId(event.getCustomerId())
                    .totalAmount(event.getTotalAmount())
                    .reservedAt(Instant.now())
                    .build();
            kafkaTemplate.send(KafkaTopics.STOCK_RESERVED, String.valueOf(event.getOrderId()), reserved);

        } catch (InsufficientStockException ex) {
            log.warn("Stock reservation failed for order {}: {}", event.getOrderId(), ex.getMessage());

            StockRejectedEvent rejected = StockRejectedEvent.builder()
                    .orderId(event.getOrderId())
                    .customerId(event.getCustomerId())
                    .reason(ex.getMessage())
                    .rejectedAt(Instant.now())
                    .build();
            kafkaTemplate.send(KafkaTopics.STOCK_REJECTED, String.valueOf(event.getOrderId()), rejected);
        }
    }
}
