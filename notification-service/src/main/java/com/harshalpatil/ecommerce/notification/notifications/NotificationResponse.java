package com.harshalpatil.ecommerce.notification.notifications;

import java.time.Instant;

public record NotificationResponse(Long id, Long customerId, Long orderId, Notification.Type type, String subject,
                                   String body, Instant createdAt) {

    static NotificationResponse from(Notification notification) {
        return new NotificationResponse(notification.getId(), notification.getCustomerId(), notification.getOrderId(),
                notification.getType(), notification.getSubject(), notification.getBody(), notification.getCreatedAt());
    }
}
