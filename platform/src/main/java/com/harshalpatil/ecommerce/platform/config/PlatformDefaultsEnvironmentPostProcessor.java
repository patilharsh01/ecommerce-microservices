package com.harshalpatil.ecommerce.platform.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Adds {@code platform-defaults.yml} as the last (lowest-precedence) property source, so every
 * service starts from the same production-minded baseline yet can override any single value.
 * Using {@code spring.config.import} instead would give the defaults <em>higher</em> precedence
 * than the importing file, which is the opposite of what defaults should do.
 */
public class PlatformDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String RESOURCE = "platform-defaults.yml";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        try {
            for (PropertySource<?> source : new YamlPropertySourceLoader().load(RESOURCE, new ClassPathResource(RESOURCE))) {
                environment.getPropertySources().addLast(source);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not load " + RESOURCE, ex);
        }
    }
}
