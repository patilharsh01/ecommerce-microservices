package com.harshalpatil.ecommerce.platform.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Every service documents the same way: titled after the application and secured with the
 * gateway's bearer token, so "Authorize" in the aggregated Swagger UI works for every API.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "io.swagger.v3.oas.models.OpenAPI")
public class PlatformOpenApiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OpenAPI platformOpenApi(Environment environment) {
        String application = environment.getProperty("spring.application.name", "service");
        return new OpenAPI()
                .info(new Info()
                        .title(application)
                        .version("2.0.0")
                        .description("Called through the API gateway. Sign in via /api/auth/login and use the "
                                + "access token as a bearer token."))
                .components(new Components().addSecuritySchemes("bearer", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
