package com.harshalpatil.ecommerce.platform.messaging;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The relay's batching rules, with Postgres and Kafka mocked. */
class OutboxRelayTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final JdbcTemplate plainJdbc = mock(JdbcTemplate.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final List<String> sentMessageIds = new ArrayList<>();
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(jdbc, new TransactionTemplate(mock(PlatformTransactionManager.class)), kafka,
                new MessageCodec(), new MessagingProperties.Outbox(true, Duration.ofSeconds(1), 200,
                Duration.ofSeconds(1), Duration.ofDays(3), 20), new SimpleMeterRegistry());
        when(jdbc.getJdbcTemplate()).thenReturn(plainJdbc);
        when(plainJdbc.queryForObject(eq("SELECT pg_try_advisory_xact_lock(?)"), eq(Boolean.class), any()))
                .thenReturn(true);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(1);
    }

    @Test
    void sendsAtMostOneMessagePerTopicAndKeyAndComesBackForTheRest() {
        batch(row(1, "orders", "7"), row(2, "orders", "7"), row(3, "orders", "8"), row(4, "events", "7"));
        kafkaAcknowledgesEverything();

        OutboxRelay.Pass pass = relay.publishBatch();

        assertThat(sentMessageIds).containsExactly("m1", "m3", "m4");
        assertThat(markedPublished()).containsExactly(1L, 3L, 4L);
        assertThat(pass.moreWaiting()).as("row 2 was deferred, so drain again right away").isTrue();
    }

    @Test
    void aFailedSendKeepsTheOtherKeysAcknowledgedAndStopsDraining() {
        batch(row(1, "orders", "7"), row(2, "orders", "8"));
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(call -> {
            ProducerRecord<String, String> record = call.getArgument(0);
            return "7".equals(record.key())
                    ? CompletableFuture.failedFuture(new IllegalStateException("broker said no"))
                    : CompletableFuture.completedFuture(mock(SendResult.class));
        });

        OutboxRelay.Pass pass = relay.publishBatch();

        assertThat(markedPublished()).containsExactly(2L);
        assertThat(pass.moreWaiting()).isFalse();
        verify(jdbc).queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class));
    }

    @Test
    void aSynchronousFailureEndsThePassWithoutTryingTheRemainingRows() {
        batch(row(1, "orders", "7"), row(2, "orders", "8"), row(3, "orders", "9"));
        when(kafka.send(any(ProducerRecord.class))).thenThrow(new IllegalStateException("metadata unavailable"));

        OutboxRelay.Pass pass = relay.publishBatch();

        verify(kafka).send(any(ProducerRecord.class));
        verify(jdbc, never()).update(anyString(), anyMap());
        assertThat(pass.moreWaiting()).isFalse();
    }

    @Test
    void anotherReplicaHoldingTheRelayLockMeansThereIsNothingToDo() {
        when(plainJdbc.queryForObject(eq("SELECT pg_try_advisory_xact_lock(?)"), eq(Boolean.class), any()))
                .thenReturn(false);

        assertThat(relay.publishBatch()).isEqualTo(OutboxRelay.Pass.NOTHING);
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    void wakingUpAfterShutdownIsHarmless() {
        relay.start();
        relay.stop();

        relay.wakeUp();
    }

    @SuppressWarnings("unchecked")
    private void batch(OutboxRelay.OutboxRow... rows) {
        when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of(rows));
    }

    private void kafkaAcknowledgesEverything() {
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(call -> {
            ProducerRecord<String, String> record = call.getArgument(0);
            sentMessageIds.add(new String(record.headers().lastHeader(MessageHeaders.MESSAGE_ID).value()));
            return CompletableFuture.completedFuture(mock(SendResult.class));
        });
    }

    @SuppressWarnings("unchecked")
    private List<Long> markedPublished() {
        ArgumentCaptor<Map<String, ?>> parameters = ArgumentCaptor.forClass(Map.class);
        verify(jdbc).update(eq("UPDATE outbox_messages SET published_at = now() WHERE id IN (:ids)"), parameters.capture());
        return (List<Long>) parameters.getValue().get("ids");
    }

    private static OutboxRelay.OutboxRow row(long id, String topic, String key) {
        return new OutboxRelay.OutboxRow(id, "m" + id, topic, key,
                "com.harshalpatil.ecommerce.common.messaging.inventory.StockReserved", "{}", "{}");
    }
}
