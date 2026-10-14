package com.harshalpatil.ecommerce.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.security.Principal;

/**
 * Buckets for the Redis token-bucket rate limiter (see the routes in application.yml).
 * Login and registration are limited per client IP to slow down credential stuffing; order
 * placement is limited per user.
 */
@Configuration(proxyBeanMethods = false)
class RateLimitKeys {

    /**
     * Trusts exactly one proxy hop (the frontend's nginx), so the address it saw is used and
     * all browser users don't share nginx's address — while a client can't pick its own
     * bucket by sending a forged X-Forwarded-For.
     */
    private static final XForwardedRemoteAddressResolver CLIENT_ADDRESS = XForwardedRemoteAddressResolver.maxTrustedIndex(1);

    @Bean
    KeyResolver clientIpKeyResolver() {
        return exchange -> Mono.justOrEmpty(CLIENT_ADDRESS.resolve(exchange))
                .map(InetSocketAddress::getAddress)
                .map(address -> "ip:" + address.getHostAddress())
                .defaultIfEmpty("ip:unknown");
    }

    @Bean
    @Primary
    KeyResolver userKeyResolver(KeyResolver clientIpKeyResolver) {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .map(name -> "user:" + name)
                .switchIfEmpty(Mono.defer(() -> clientIpKeyResolver.resolve(exchange)));
    }
}
