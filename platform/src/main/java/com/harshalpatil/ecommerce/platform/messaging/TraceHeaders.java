package com.harshalpatil.ecommerce.platform.messaging;

import java.util.Map;

/**
 * Captures the current trace context as headers (W3C {@code traceparent}) so it can be
 * stored on an outbox row. The relay publishes on a different thread long after the
 * request span has ended; copying the headers onto the Kafka record lets the consumer's
 * span join the original trace instead of starting a new one.
 */
@FunctionalInterface
public interface TraceHeaders {

    TraceHeaders NONE = Map::of;

    Map<String, String> current();
}
