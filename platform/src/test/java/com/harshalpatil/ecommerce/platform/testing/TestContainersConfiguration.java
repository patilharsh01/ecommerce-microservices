package com.harshalpatil.ecommerce.platform.testing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Postgres and Kafka for integration tests, wired into Spring Boot through
 * {@link ServiceConnection} (no hand-written datasource or bootstrap-server properties).
 * Shared with every service through the platform test-jar; import it with
 * {@code @Import(TestContainersConfiguration.class)}. Kafka is only started for services
 * that actually use it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestContainersConfiguration {

    public static final DockerImageName POSTGRES = DockerImageName.parse("postgres:17-alpine");
    public static final DockerImageName KAFKA = DockerImageName.parse("apache/kafka-native:4.1.2");

    @Bean
    @ServiceConnection
    public PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(POSTGRES);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
    public static class KafkaContainerConfiguration {

        @Bean
        @ServiceConnection
        public KafkaContainer kafka() {
            return new KafkaContainer(KAFKA);
        }
    }
}
