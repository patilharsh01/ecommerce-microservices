package com.harshalpatil.ecommerce.common.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Anything that travels over Kafka. {@link #messageId()} is the de-duplication key:
 * consumers record it in their inbox table in the same transaction as their side
 * effects, which turns Kafka's at-least-once delivery into effectively-once processing.
 */
public interface Message {

    UUID messageId();

    Instant occurredAt();
}
