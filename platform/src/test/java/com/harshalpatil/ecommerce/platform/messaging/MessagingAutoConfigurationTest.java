package com.harshalpatil.ecommerce.platform.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MessagingAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MessagingAutoConfiguration.class))
            .withPropertyValues("spring.application.name=test-service")
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(NamedParameterJdbcTemplate.class, () -> mock(NamedParameterJdbcTemplate.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class));

    @Test
    void wiresOutboxInboxAndDeadLetteringByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(OutboxWriter.class);
            assertThat(context).hasSingleBean(OutboxRelay.class);
            assertThat(context).hasSingleBean(InboxProcessor.class);
            assertThat(context).hasSingleBean(CommonErrorHandler.class);
            assertThat(context).hasSingleBean(TraceHeaders.class);
        });
    }

    @Test
    void consumerOnlyServicesCanSwitchTheOutboxOff() {
        runner.withPropertyValues("platform.messaging.outbox.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(OutboxWriter.class);
            assertThat(context).doesNotHaveBean(OutboxRelay.class);
            assertThat(context).hasSingleBean(InboxProcessor.class);
        });
    }

    @Test
    void createsEveryConfiguredTopicTogetherWithItsDeadLetterTopic() {
        runner.withPropertyValues("platform.messaging.topics=orders,payments", "platform.messaging.partitions=6")
                .run(context -> {
                    assertThat(context).hasSingleBean(KafkaAdmin.NewTopics.class);
                    List<NewTopic> topics = MessagingAutoConfiguration.declaredTopics(context.getBean(MessagingProperties.class));
                    assertThat(topics).extracting(NewTopic::name)
                            .containsExactly("orders", "orders.DLT", "payments", "payments.DLT");
                    assertThat(topics).allSatisfy(topic -> assertThat(topic.numPartitions()).isEqualTo(6));
                });
    }

    @Test
    void anApplicationDefinedErrorHandlerWins() {
        CommonErrorHandler custom = mock(CommonErrorHandler.class);
        runner.withBean("customErrorHandler", CommonErrorHandler.class, () -> custom)
                .run(context -> assertThat(context.getBean(CommonErrorHandler.class)).isSameAs(custom));
    }
}
