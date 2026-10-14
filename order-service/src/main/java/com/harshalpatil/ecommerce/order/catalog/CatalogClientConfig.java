package com.harshalpatil.ecommerce.order.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.DeferringLoadBalancerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
class CatalogClientConfig {

    /**
     * {@code http://product-service} is resolved through Eureka by the load-balancer
     * interceptor. The RestClient is built privately on purpose: publishing a
     * {@code @LoadBalanced RestClient.Builder} bean would replace Boot's shared builder, and
     * the Eureka client builds its own transport from that bean — its registration calls would
     * then be load-balanced too, and fail.
     * <p>
     * Tight timeouts: placing an order waits on this call, and a slow catalog should trip the
     * circuit breaker rather than tie up request threads.
     */
    @Bean
    CatalogClient catalogClient(DeferringLoadBalancerInterceptor loadBalancer,
                                @Value("${order.catalog.base-url:http://product-service}") String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(3));

        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(loadBalancer)
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(CatalogClient.class);
    }
}
