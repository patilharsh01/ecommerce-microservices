package com.harshalpatil.ecommerce.platform.messaging;

/**
 * The record can never be processed no matter how often it's retried, so the Kafka error
 * handler sends it straight to the dead-letter topic.
 */
public class MessageDecodingException extends RuntimeException {

    public MessageDecodingException(String message) {
        super(message);
    }

    public MessageDecodingException(String message, Throwable cause) {
        super(message, cause);
    }
}
