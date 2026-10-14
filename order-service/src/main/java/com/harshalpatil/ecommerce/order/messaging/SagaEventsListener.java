package com.harshalpatil.ecommerce.order.messaging;

import com.harshalpatil.ecommerce.common.enums.OrderStatus;
import com.harshalpatil.ecommerce.common.event.PaymentCompletedEvent;
import com.harshalpatil.ecommerce.common.event.PaymentFailedEvent;
import com.harshalpatil.ecommerce.common.event.StockRejectedEvent;
import com.harshalpatil.ecommerce.common.event.StockReservedEvent;
import com.harshalpatil.ecommerce.common.kafka.KafkaTopics;
import com.harshalpatil.ecommerce.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * order-service is the source of truth for order status, so it listens to
 * every downstream event of the saga (stock, then payment) and reflects the
 * outcome back onto the Order row. This is a choreography-based saga: no
 * central orchestrator, each service reacts to the previous one's event.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SagaEventsListener {

    private final OrderService orderService;

    @KafkaListener(topics = KafkaTopics.STOCK_RESERVED, groupId = "order-service")
    public void onStockReserved(StockReservedEvent event) {
        log.info("Stock reserved for order {}", event.getOrderId());
        orderService.updateStatus(event.getOrderId(), OrderStatus.STOCK_RESERVED);
    }

    @KafkaListener(topics = KafkaTopics.STOCK_REJECTED, groupId = "order-service")
    public void onStockRejected(StockRejectedEvent event) {
        log.info("Stock rejected for order {}: {}", event.getOrderId(), event.getReason());
        orderService.updateStatus(event.getOrderId(), OrderStatus.CANCELLED);
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_COMPLETED, groupId = "order-service")
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        log.info("Payment completed for order {}", event.getOrderId());
        orderService.updateStatus(event.getOrderId(), OrderStatus.CONFIRMED);
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_FAILED, groupId = "order-service")
    public void onPaymentFailed(PaymentFailedEvent event) {
        log.info("Payment failed for order {}: {}", event.getOrderId(), event.getReason());
        orderService.updateStatus(event.getOrderId(), OrderStatus.CANCELLED);
    }
}
