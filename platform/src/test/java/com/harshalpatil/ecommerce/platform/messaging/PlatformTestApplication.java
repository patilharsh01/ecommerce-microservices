package com.harshalpatil.ecommerce.platform.messaging;

import com.harshalpatil.ecommerce.common.messaging.Message;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/** Minimal application that exercises the platform auto-configuration end to end. */
@SpringBootApplication
class PlatformTestApplication {

    static final String TOPIC = "platform.test";
    static final String POISON_TOPIC = "platform.poison";

    /** Captures what the relay actually put on Kafka. */
    @Component
    static class RecordingListener {

        final BlockingQueue<ConsumerRecord<String, String>> received = new LinkedBlockingQueue<>();
        final BlockingQueue<ConsumerRecord<String, String>> deadLetters = new LinkedBlockingQueue<>();

        @KafkaListener(topics = TOPIC, groupId = "platform-test-recorder")
        void onMessage(ConsumerRecord<String, String> record) {
            received.add(record);
        }

        @KafkaListener(topics = POISON_TOPIC + ".DLT", groupId = "platform-test-dlt")
        void onDeadLetter(ConsumerRecord<String, String> record) {
            deadLetters.add(record);
        }
    }

    /** A real consumer of the poison topic that goes through the inbox, like the services do. */
    @Component
    static class InboxBackedListener {

        final List<Message> handled = new CopyOnWriteArrayList<>();
        private final InboxProcessor inbox;

        InboxBackedListener(InboxProcessor inbox) {
            this.inbox = inbox;
        }

        @KafkaListener(topics = POISON_TOPIC, groupId = "platform-test-inbox")
        void onMessage(ConsumerRecord<String, String> record) {
            inbox.process(record, handled::add);
        }
    }
}
