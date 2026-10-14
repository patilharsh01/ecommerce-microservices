package com.harshalpatil.ecommerce.platform.messaging;

import com.harshalpatil.ecommerce.common.messaging.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Wires the outbox, the inbox and dead-lettering into any service that has both Kafka and JDBC
 * on its classpath. The service only provides the two tables (Flyway scripts ship with this
 * module under {@code db/platform}) and lists its topics under {@code platform.messaging.topics}.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
        "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"})
@ConditionalOnClass({KafkaTemplate.class, JdbcTemplate.class})
@EnableConfigurationProperties(MessagingProperties.class)
@EnableScheduling
public class MessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    MessageCodec messageCodec() {
        return new MessageCodec();
    }

    @Bean
    @ConditionalOnMissingBean
    InboxProcessor inboxProcessor(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                  MessageCodec codec, MessagingProperties properties,
                                  ObjectProvider<MeterRegistry> meterRegistry, Environment environment) {
        return new InboxProcessor(jdbc, new TransactionTemplate(transactionManager), codec, properties.inbox(),
                meterRegistry.getIfAvailable(SimpleMeterRegistry::new),
                environment.getRequiredProperty("spring.application.name"));
    }

    /**
     * Retries a failing record with exponential back-off, then parks it on {@code <topic>.DLT}
     * (original headers plus exception details) so one poison message can't block its partition.
     * Records that can't even be decoded skip the retries.
     */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate, MessagingProperties properties,
                                          ObjectProvider<MeterRegistry> meterRegistry) {
        MeterRegistry registry = meterRegistry.getIfAvailable(SimpleMeterRegistry::new);
        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> {
                    registry.counter("messaging.dead.letters", "topic", record.topic()).increment();
                    return new TopicPartition(record.topic() + Topics.DEAD_LETTER_SUFFIX, -1);
                });

        MessagingProperties.Retry retry = properties.retry();
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retry.maxRetries());
        backOff.setInitialInterval(retry.initialInterval().toMillis());
        backOff.setMultiplier(retry.multiplier());

        DefaultErrorHandler handler = new DefaultErrorHandler(deadLetters, backOff);
        handler.addNotRetryableExceptions(MessageDecodingException.class);
        return handler;
    }

    @Bean
    KafkaAdmin.NewTopics platformTopics(MessagingProperties properties) {
        return new KafkaAdmin.NewTopics(declaredTopics(properties).toArray(NewTopic[]::new));
    }

    static List<NewTopic> declaredTopics(MessagingProperties properties) {
        List<NewTopic> topics = new ArrayList<>();
        for (String name : properties.topics()) {
            topics.add(topic(name, properties));
            topics.add(topic(name + Topics.DEAD_LETTER_SUFFIX, properties));
        }
        return topics;
    }

    private static NewTopic topic(String name, MessagingProperties properties) {
        return TopicBuilder.name(name)
                .partitions(properties.partitions())
                .replicas(properties.replicationFactor())
                .build();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "platform.messaging.outbox.enabled", matchIfMissing = true)
    static class OutboxConfiguration {

        @Bean
        OutboxRelay outboxRelay(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                KafkaTemplate<String, String> kafkaTemplate, MessageCodec codec,
                                MessagingProperties properties, ObjectProvider<MeterRegistry> meterRegistry) {
            return new OutboxRelay(jdbc, new TransactionTemplate(transactionManager), kafkaTemplate, codec,
                    properties.outbox(), meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
        }

        @Bean
        OutboxWriter outboxWriter(JdbcTemplate jdbc, MessageCodec codec, TraceHeaders traceHeaders, OutboxRelay relay) {
            return new OutboxWriter(jdbc, codec, traceHeaders, relay);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
    static class TracingConfiguration {

        @Bean
        @ConditionalOnMissingBean
        TraceHeaders traceHeaders(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
            return new MicrometerTraceHeaders(tracer, propagator);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    TraceHeaders noTraceHeaders() {
        return TraceHeaders.NONE;
    }
}
