package com.harshalpatil.ecommerce.notification.messaging;

import com.harshalpatil.ecommerce.common.event.*;
import com.harshalpatil.ecommerce.common.kafka.KafkaTopics;
import com.harshalpatil.ecommerce.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Notification-service sits at the end of every branch of the saga — it
 * never changes state, only observes it, so it's the safest place to see
 * the whole order lifecycle end-to-end during a demo.
 */
@Component
@RequiredArgsConstructor
public class SagaEventsListener {

    private final NotificationService notificationService;

    @KafkaListener(topics = KafkaTopics.ORDER_CREATED, groupId = "notification-service")
    public void onOrderCreated(OrderCreatedEvent event) {
        notificationService.send(event.getOrderId(), event.getCustomerId(),
                "Order placed",
                "Your order #" + event.getOrderId() + " for " + event.getTotalAmount() + " has been received.");
    }

    @KafkaListener(topics = KafkaTopics.STOCK_REJECTED, groupId = "notification-service")
    public void onStockRejected(StockRejectedEvent event) {
        notificationService.send(event.getOrderId(), event.getCustomerId(),
                "Order could not be fulfilled",
                "Order #" + event.getOrderId() + " was cancelled: " + event.getReason());
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_COMPLETED, groupId = "notification-service")
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        notificationService.send(event.getOrderId(), event.getCustomerId(),
                "Payment successful",
                "Payment of " + event.getAmount() + " for order #" + event.getOrderId()
                        + " succeeded (ref " + event.getPaymentReference() + "). Your order is confirmed.");
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_FAILED, groupId = "notification-service")
    public void onPaymentFailed(PaymentFailedEvent event) {
        notificationService.send(event.getOrderId(), event.getCustomerId(),
                "Payment failed",
                "Payment for order #" + event.getOrderId() + " failed: " + event.getReason());
    }
}
