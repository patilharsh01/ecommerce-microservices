package com.harshalpatil.ecommerce.platform.messaging;

/** Kafka record headers written by the outbox relay. */
public final class MessageHeaders {

    public static final String MESSAGE_ID = "message-id";
    public static final String MESSAGE_TYPE = "message-type";

    private MessageHeaders() {
    }
}
