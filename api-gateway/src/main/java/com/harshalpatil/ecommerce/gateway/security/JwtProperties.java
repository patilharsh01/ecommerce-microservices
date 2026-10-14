package com.harshalpatil.ecommerce.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param jwkSetUri where auth-service publishes its public keys; {@code http://auth-service}
 *                  is a Eureka service id, resolved by the load balancer
 * @param issuer    expected {@code iss}
 * @param audience  expected entry in {@code aud}
 */
@ConfigurationProperties("gateway.jwt")
public record JwtProperties(
        @DefaultValue("http://auth-service/.well-known/jwks.json") String jwkSetUri,
        @DefaultValue("http://auth-service") String issuer,
        @DefaultValue("ecommerce-api") String audience) {
}
