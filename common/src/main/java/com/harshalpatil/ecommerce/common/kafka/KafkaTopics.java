package com.harshalpatil.ecommerce.common.kafka;

/**
 * Central registry of Kafka topic names shared by every service so producers
 * and consumers never drift apart on a hardcoded string.
 */
public final class KafkaTopics {

    private KafkaTopics() {
    }

    public static final String ORDER_CREATED = "order.created";
    public static final String STOCK_RESERVED = "stock.reserved";
    public static final String STOCK_REJECTED = "stock.rejected";
    public static final String PAYMENT_COMPLETED = "payment.completed";
    public static final String PAYMENT_FAILED = "payment.failed";
}
