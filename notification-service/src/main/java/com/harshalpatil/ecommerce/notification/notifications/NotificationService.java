package com.harshalpatil.ecommerce.notification.notifications;

import com.harshalpatil.ecommerce.common.messaging.Message;
import com.harshalpatil.ecommerce.common.messaging.order.OrderCancelled;
import com.harshalpatil.ecommerce.common.messaging.order.OrderConfirmed;
import com.harshalpatil.ecommerce.common.messaging.order.OrderPlaced;
import com.harshalpatil.ecommerce.platform.format.Rupees;
import com.harshalpatil.ecommerce.platform.web.AuthenticatedUser;
import com.harshalpatil.ecommerce.platform.web.PageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notifications;
    private final NotificationSender sender;

    /** Runs inside the inbox transaction; the provider is only called once that commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(Message event) {
        Optional<Notification> notification = toNotification(event);
        if (notification.isEmpty()) {
            log.debug("No notification for {}", event.getClass().getSimpleName());
            return;
        }
        Notification saved = notifications.save(notification.get());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sender.send(saved);
            }
        });
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(AuthenticatedUser user, Long customerId, Long orderId,
                                                   Pageable pageable) {
        Page<Notification> page;
        if (user.isAdmin() && customerId == null) {
            page = orderId == null ? notifications.findAll(pageable) : notifications.findByOrderId(orderId, pageable);
        } else {
            // Customers only ever see their own, whatever customerId they ask for.
            Long owner = user.isAdmin() ? customerId : user.id();
            page = orderId == null ? notifications.findByCustomerId(owner, pageable)
                    : notifications.findByCustomerIdAndOrderId(owner, orderId, pageable);
        }
        return PageResponse.of(page, NotificationResponse::from);
    }

    static Optional<Notification> toNotification(Message event) {
        if (event instanceof OrderPlaced placed) {
            return Optional.of(new Notification(placed.customerId(), placed.orderId(), Notification.Type.ORDER_PLACED,
                    "We received order #" + placed.orderId(),
                    "Thanks! Order #" + placed.orderId() + " for " + Rupees.format(placed.totalAmount()) + " (" + placed.itemCount()
                            + " item(s)) is being processed. We'll email you once payment is confirmed."));
        }
        if (event instanceof OrderConfirmed confirmed) {
            return Optional.of(new Notification(confirmed.customerId(), confirmed.orderId(), Notification.Type.ORDER_CONFIRMED,
                    "Order #" + confirmed.orderId() + " is confirmed",
                    "Payment of " + Rupees.format(confirmed.totalAmount()) + " was successful (reference "
                            + confirmed.paymentReference() + "). Your order is confirmed."));
        }
        if (event instanceof OrderCancelled cancelled) {
            return Optional.of(new Notification(cancelled.customerId(), cancelled.orderId(), Notification.Type.ORDER_CANCELLED,
                    "Order #" + cancelled.orderId() + " was cancelled",
                    "Order #" + cancelled.orderId() + " could not be completed: " + cancelled.reason()
                            + ". You have not been charged for it."));
        }
        return Optional.empty();
    }
}
