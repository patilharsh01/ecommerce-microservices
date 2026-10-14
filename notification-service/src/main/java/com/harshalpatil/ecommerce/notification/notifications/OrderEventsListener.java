package com.harshalpatil.ecommerce.notification.notifications;

import com.harshalpatil.ecommerce.common.messaging.Topics;
import com.harshalpatil.ecommerce.platform.messaging.InboxProcessor;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** De-duplicated through the inbox: a redelivered event never emails the customer twice. */
@Component
@RequiredArgsConstructor
class OrderEventsListener {

    private final InboxProcessor inbox;
    private final NotificationService notifications;

    @KafkaListener(topics = Topics.ORDER_EVENTS)
    void onEvent(ConsumerRecord<String, String> record) {
        inbox.process(record, notifications::handle);
    }
}
