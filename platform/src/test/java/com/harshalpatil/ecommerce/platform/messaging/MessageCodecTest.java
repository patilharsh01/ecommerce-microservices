package com.harshalpatil.ecommerce.platform.messaging;

import com.harshalpatil.ecommerce.common.messaging.Message;
import com.harshalpatil.ecommerce.common.messaging.inventory.OrderLine;
import com.harshalpatil.ecommerce.common.messaging.inventory.ReserveStockCommand;
import com.harshalpatil.ecommerce.common.messaging.payment.ProcessPaymentCommand;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageCodecTest {

    private final MessageCodec codec = new MessageCodec();

    @Test
    void roundTripsARecordThroughJsonAndTheTypeHeader() {
        ReserveStockCommand command = ReserveStockCommand.of(42L, List.of(new OrderLine(7L, 3), new OrderLine(9L, 1)));

        Message decoded = codec.decode(record(codec.encode(command), codec.typeOf(command)));

        assertThat(decoded).isEqualTo(command);
    }

    @Test
    void keepsBigDecimalPrecision() {
        ProcessPaymentCommand command = ProcessPaymentCommand.of(1L, 2L, new BigDecimal("1234.50"));

        ProcessPaymentCommand decoded = (ProcessPaymentCommand) codec.decode(codec.encode(command), codec.typeOf(command));

        assertThat(decoded.amount()).isEqualByComparingTo("1234.50");
    }

    @Test
    void ignoresFieldsItDoesNotKnowSoProducersCanEvolveFirst() {
        String payload = """
                {"messageId":"6b0f1c8e-2a43-4bfa-9d0e-4e1f5d7c9a11","orderId":5,"occurredAt":"2026-01-01T10:00:00Z",
                 "addedInAFutureVersion":"whatever"}""";

        Message decoded = codec.decode(payload, "com.harshalpatil.ecommerce.common.messaging.inventory.StockReserved");

        assertThat(decoded.messageId()).hasToString("6b0f1c8e-2a43-4bfa-9d0e-4e1f5d7c9a11");
    }

    @Test
    void refusesTypesOutsideTheContractsPackage() {
        assertThatThrownBy(() -> codec.decode("{}", "java.util.HashMap"))
                .isInstanceOf(MessageDecodingException.class)
                .hasMessageContaining("outside the contracts package");
    }

    @Test
    void refusesClassesInTheContractsPackageThatAreNotMessages() {
        assertThatThrownBy(() -> codec.decode("{}", "com.harshalpatil.ecommerce.common.messaging.Topics"))
                .isInstanceOf(MessageDecodingException.class);
    }

    @Test
    void rejectsRecordsWithoutATypeHeader() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("topic", 0, 0L, "key", "{}");

        assertThatThrownBy(() -> codec.decode(record)).isInstanceOf(MessageDecodingException.class);
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> codec.decode("{not json", "com.harshalpatil.ecommerce.common.messaging.inventory.StockReserved"))
                .isInstanceOf(MessageDecodingException.class);
    }

    @Test
    void roundTripsHeaderMaps() {
        Map<String, String> headers = Map.of("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        assertThat(codec.decodeHeaders(codec.encodeHeaders(headers))).isEqualTo(headers);
        assertThat(codec.decodeHeaders(null)).isEmpty();
    }

    private static ConsumerRecord<String, String> record(String payload, String type) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("topic", 0, 0L, "key", payload);
        record.headers().add(MessageHeaders.MESSAGE_TYPE, type.getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
