package com.harshalpatil.ecommerce.platform.messaging;

import com.harshalpatil.ecommerce.common.messaging.Message;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wire format for every message: JSON payload, Java type carried in the {@code message-type}
 * header. Uses its own mapper rather than the application's so the wire format can't drift
 * when a service tweaks its HTTP JSON settings.
 * <p>
 * Only types from the shared contracts package can be instantiated. Resolving arbitrary class
 * names from a header would let anyone who can write to a topic choose what gets deserialized.
 */
public class MessageCodec {

    static final String CONTRACTS_PACKAGE = "com.harshalpatil.ecommerce.common.messaging.";

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final Map<String, Class<? extends Message>> typeCache = new ConcurrentHashMap<>();

    public String encode(Message message) {
        return mapper.writeValueAsString(message);
    }

    public String typeOf(Message message) {
        return message.getClass().getName();
    }

    public Message decode(ConsumerRecord<String, String> record) {
        Header typeHeader = record.headers().lastHeader(MessageHeaders.MESSAGE_TYPE);
        if (typeHeader == null) {
            throw new MessageDecodingException("Record at " + coordinates(record) + " has no "
                    + MessageHeaders.MESSAGE_TYPE + " header");
        }
        if (record.value() == null) {
            throw new MessageDecodingException("Record at " + coordinates(record) + " has an empty payload");
        }
        return decode(record.value(), new String(typeHeader.value(), StandardCharsets.UTF_8));
    }

    public Message decode(String payload, String type) {
        Class<? extends Message> messageClass = resolve(type);
        try {
            return mapper.readValue(payload, messageClass);
        } catch (JacksonException ex) {
            throw new MessageDecodingException("Payload is not a valid " + messageClass.getSimpleName(), ex);
        }
    }

    public String encodeHeaders(Map<String, String> headers) {
        return mapper.writeValueAsString(headers);
    }

    public Map<String, String> decodeHeaders(String json) {
        return json == null ? Map.of() : mapper.readValue(json, STRING_MAP);
    }

    private Class<? extends Message> resolve(String type) {
        if (!type.startsWith(CONTRACTS_PACKAGE)) {
            throw new MessageDecodingException("Refusing to deserialize type outside the contracts package: " + type);
        }
        return typeCache.computeIfAbsent(type, name -> {
            try {
                Class<?> candidate = Class.forName(name, false, MessageCodec.class.getClassLoader());
                if (!Message.class.isAssignableFrom(candidate)) {
                    throw new MessageDecodingException(name + " is not a " + Message.class.getSimpleName());
                }
                return candidate.asSubclass(Message.class);
            } catch (ClassNotFoundException ex) {
                throw new MessageDecodingException("Unknown message type " + name, ex);
            }
        });
    }

    private static String coordinates(ConsumerRecord<?, ?> record) {
        return record.topic() + "-" + record.partition() + "@" + record.offset();
    }
}
