package com.harshalpatil.ecommerce.common.messaging;

/**
 * Every message in the order saga is about exactly one order. The order id is also the
 * Kafka record key, so all messages for one order land on the same partition and are
 * consumed in the order they were produced.
 */
public interface OrderMessage extends Message {

    Long orderId();
}
