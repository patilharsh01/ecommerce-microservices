package com.harshalpatil.ecommerce.gateway.security;

import com.harshalpatil.ecommerce.common.security.IdentityHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ServerWebExchange;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityPropagationFilterTest {

    @Test
    void anonymousCallersCannotSmuggleInAnIdentity() {
        ServerWebExchange spoofed = exchange(MockServerHttpRequest.get("/api/orders")
                .header(IdentityHeaders.USER_ID, "1")
                .header(IdentityHeaders.USER_ROLES, "ADMIN"));

        ServerHttpRequest forwarded = IdentityPropagationFilter.rewrite(spoofed, null).getRequest();

        assertThat(forwarded.getHeaders().containsHeader(IdentityHeaders.USER_ID)).isFalse();
        assertThat(forwarded.getHeaders().containsHeader(IdentityHeaders.USER_ROLES)).isFalse();
    }

    @Test
    void authenticatedCallersGetHeadersFromTheVerifiedTokenOnly() {
        ServerWebExchange spoofed = exchange(MockServerHttpRequest.get("/api/orders")
                .header(IdentityHeaders.USER_ID, "1")
                .header(IdentityHeaders.USER_ROLES, "ADMIN")
                .header(HttpHeaders.AUTHORIZATION, "Bearer abc"));

        ServerHttpRequest forwarded = IdentityPropagationFilter.rewrite(spoofed, jwt("42", List.of("CUSTOMER"))).getRequest();

        assertThat(forwarded.getHeaders().get(IdentityHeaders.USER_ID)).containsExactly("42");
        assertThat(forwarded.getHeaders().get(IdentityHeaders.USER_ROLES)).containsExactly("CUSTOMER");
        assertThat(forwarded.getHeaders().containsHeader(HttpHeaders.AUTHORIZATION))
                .as("tokens stop at the edge").isFalse();
    }

    private static ServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> request) {
        return MockServerWebExchange.from(request);
    }

    private static Jwt jwt(String subject, List<String> roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("roles", roles)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
