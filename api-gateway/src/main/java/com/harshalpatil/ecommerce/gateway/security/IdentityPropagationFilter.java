package com.harshalpatil.ecommerce.gateway.security;

import com.harshalpatil.ecommerce.common.security.IdentityHeaders;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Turns the verified token into the identity headers downstream services trust.
 * <ol>
 *   <li>Always strips client-supplied {@code X-User-Id} / {@code X-User-Roles} first — otherwise
 *       anyone could claim to be user 1 by sending the header themselves.</li>
 *   <li>Sets them from the verified JWT when the caller is authenticated.</li>
 *   <li>Drops the {@code Authorization} header: bearer tokens stop at the edge, so a leaky
 *       downstream log can't replay them.</li>
 * </ol>
 */
@Component
public class IdentityPropagationFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(principal -> rewrite(exchange, ((JwtAuthenticationToken) principal).getToken()))
                .switchIfEmpty(Mono.fromSupplier(() -> rewrite(exchange, null)))
                .flatMap(chain::filter);
    }

    static ServerWebExchange rewrite(ServerWebExchange exchange, Jwt jwt) {
        return exchange.mutate().request(request -> request.headers(headers -> {
            headers.remove(IdentityHeaders.USER_ID);
            headers.remove(IdentityHeaders.USER_ROLES);
            headers.remove(HttpHeaders.AUTHORIZATION);
            if (jwt != null) {
                List<String> roles = jwt.getClaimAsStringList("roles");
                headers.set(IdentityHeaders.USER_ID, jwt.getSubject());
                headers.set(IdentityHeaders.USER_ROLES, roles == null ? "" : String.join(",", roles));
            }
        })).build();
    }

    /** Before the rate limiter and the routing filters. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
