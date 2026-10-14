package com.harshalpatil.ecommerce.notification.notifications;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Development stand-in: "sends" by logging. A real provider would be called from an
 * after-commit hook (or its own outbox) so a rolled-back transaction never emails anyone.
 */
@Component
@Slf4j
class LoggingNotificationSender implements NotificationSender {

    @Override
    public void send(Notification notification) {
        log.info("EMAIL to customer {} | {} | {}", notification.getCustomerId(), notification.getSubject(),
                notification.getBody());
    }
}
