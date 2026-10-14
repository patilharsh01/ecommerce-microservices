package com.harshalpatil.ecommerce.platform.testing;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Full application context against real Postgres and Kafka containers.
 * Skipped (not failed) on machines without a Docker daemon; CI always has one.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tag("integration")
@SpringBootTest
@Import(TestContainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
public @interface IntegrationTest {

    @AliasFor(annotation = SpringBootTest.class, attribute = "properties")
    String[] properties() default {};
}
