package com.harshalpatil.ecommerce.gateway.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.config.EnableWebFlux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/** The edge's access rules, with a stub decoder instead of auth-service's JWK Set. */
@SpringJUnitConfig({GatewaySecurityConfig.class, GatewaySecurityRulesTest.TestConfig.class})
class GatewaySecurityRulesTest {

    @Autowired
    ApplicationContext context;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    @Test
    void catalogBrowsingAndSignInAreOpen() {
        client.get().uri("/api/products").exchange().expectStatus().isOk();
        client.post().uri("/api/auth/login").exchange().expectStatus().isOk();
    }

    @Test
    void everythingElseNeedsAToken() {
        client.post().uri("/api/orders").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
        client.post().uri("/api/products").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void invalidOrExpiredTokensAreRejected() {
        client.post().uri("/api/orders").header("Authorization", "Bearer forged").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void validTokensAreAccepted() {
        client.post().uri("/api/orders").header("Authorization", "Bearer valid").exchange()
                .expectStatus().isOk();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    static class TestConfig {

        @Bean
        ReactiveJwtDecoder jwtDecoder() {
            return token -> "valid".equals(token)
                    ? Mono.just(Jwt.withTokenValue(token).header("alg", "RS256").subject("42")
                    .claim("roles", List.of("CUSTOMER")).issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(60)).build())
                    : Mono.error(new BadJwtException("bad signature"));
        }

        @Bean
        StubRoutes stubRoutes() {
            return new StubRoutes();
        }
    }

    @RestController
    static class StubRoutes {

        @GetMapping("/api/products")
        String products() {
            return "[]";
        }

        @PostMapping({"/api/auth/login", "/api/orders", "/api/products"})
        String accepted() {
            return "ok";
        }
    }
}
