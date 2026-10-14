package com.harshalpatil.ecommerce.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Resolved through Eureka by logical service name — no hardcoded host/port.
 * Called synchronously at order-creation time to price the order; everything
 * after that (stock reservation, payment, notification) happens async over Kafka.
 */
@FeignClient(name = "product-service")
public interface ProductClient {

    @GetMapping("/api/products/{id}")
    ProductDto getProduct(@PathVariable("id") Long id);
}
