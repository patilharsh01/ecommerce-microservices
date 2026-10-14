package com.harshalpatil.ecommerce.product.inventory;

import com.harshalpatil.ecommerce.common.messaging.Topics;
import com.harshalpatil.ecommerce.common.messaging.inventory.ReleaseStockCommand;
import com.harshalpatil.ecommerce.common.messaging.inventory.ReserveStockCommand;
import com.harshalpatil.ecommerce.platform.messaging.InboxProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
class InventoryCommandListener {

    private final InboxProcessor inbox;
    private final InventoryService inventory;

    @KafkaListener(topics = Topics.INVENTORY_COMMANDS)
    void onCommand(ConsumerRecord<String, String> record) {
        inbox.process(record, message -> {
            if (message instanceof ReserveStockCommand command) {
                inventory.reserve(command);
            } else if (message instanceof ReleaseStockCommand command) {
                inventory.release(command);
            } else {
                log.warn("Ignoring unsupported {} on {}", message.getClass().getSimpleName(), record.topic());
            }
        });
    }
}
