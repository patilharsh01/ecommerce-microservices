package com.harshalpatil.ecommerce.notification.service;

import com.harshalpatil.ecommerce.notification.model.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Stands in for a real email/SMS provider (SES, Twilio, etc). Every "send"
 * is logged and kept in an in-memory history so the /api/notifications
 * endpoint can prove, during a demo, that the event actually arrived and was
 * acted on — swap sendInternal() for a real provider call in production.
 */
@Service
@Slf4j
public class NotificationService {

    private static final int MAX_HISTORY = 200;
    private final ConcurrentLinkedDeque<Notification> history = new ConcurrentLinkedDeque<>();

    public void send(Long orderId, Long customerId, String subject, String message) {
        Notification notification = Notification.builder()
                .orderId(orderId)
                .customerId(customerId)
                .subject(subject)
                .message(message)
                .sentAt(Instant.now())
                .build();

        history.addFirst(notification);
        while (history.size() > MAX_HISTORY) {
            history.removeLast();
        }

        log.info("EMAIL to customer {} | subject='{}' | {}", customerId, subject, message);
    }

    public List<Notification> recent(Long customerId) {
        return List.copyOf(history).stream()
                .filter(n -> customerId == null || n.getCustomerId().equals(customerId))
                .sorted(Comparator.comparing(Notification::getSentAt).reversed())
                .toList();
    }
}
