package com.harshalpatil.ecommerce.auth.token;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;

/**
 * @param issuer       {@code iss} claim; the gateway rejects tokens from anyone else
 * @param audience     {@code aud} claim; the gateway rejects tokens minted for other systems
 * @param ttl          access-token lifetime
 * @param keyDirectory where the RSA signing key is kept between restarts; when unset an
 *                     in-memory key is generated (fine for tests, logs everyone out on restart)
 */
@ConfigurationProperties("security.jwt")
public record TokenProperties(
        @DefaultValue("http://auth-service") String issuer,
        @DefaultValue("ecommerce-api") String audience,
        @DefaultValue("60m") Duration ttl,
        Path keyDirectory) {
}
