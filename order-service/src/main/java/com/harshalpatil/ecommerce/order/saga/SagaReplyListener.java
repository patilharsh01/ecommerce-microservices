package com.harshalpatil.ecommerce.order.saga;

import com.harshalpatil.ecommerce.common.messaging.OrderMessage;
import com.harshalpatil.ecommerce.common.messaging.Topics;
import com.harshalpatil.ecommerce.platform.messaging.InboxProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Every participant answers on one topic keyed by order id, so the replies for an order are
 * handled one at a time, in the order they were produced.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class SagaReplyListener {

    private final InboxProcessor inbox;
    private final OrderSagaOrchestrator orchestrator;

    @KafkaListener(topics = Topics.ORDER_SAGA_REPLIES)
    void onReply(ConsumerRecord<String, String> record) {
        inbox.process(record, message -> {
            if (message instanceof OrderMessage reply) {
                orchestrator.onReply(reply);
            } else {
                log.warn("Ignoring non-order message {} on {}", message.getClass().getSimpleName(), record.topic());
            }
        });
    }
}
