package com.harshalpatil.ecommerce.gateway.security;

import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Configuration(proxyBeanMethods = false)
class JwtDecoderConfig {

    /**
     * Verifies RS256 signatures against auth-service's JWK Set — the gateway holds no secret
     * that could mint tokens. Keys are fetched lazily and cached; a token signed with an
     * unknown key id triggers a refetch, so key rotation needs no gateway restart. Signature,
     * expiry (with clock skew), issuer and audience must all check out.
     * <p>
     * The JWK Set client gets the load-balancer filter directly rather than through a shared
     * {@code @LoadBalanced WebClient.Builder} bean, which would also capture unrelated clients.
     */
    @Bean
    ReactiveJwtDecoder jwtDecoder(ReactorLoadBalancerExchangeFilterFunction loadBalancer, JwtProperties properties) {
        WebClient jwksClient = WebClient.builder().filter(loadBalancer).build();
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .webClient(jwksClient)
                .build();
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(properties.audience()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()), audience));
        return decoder;
    }
}
