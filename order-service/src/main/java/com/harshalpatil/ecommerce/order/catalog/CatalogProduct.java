package com.harshalpatil.ecommerce.order.catalog;

import java.math.BigDecimal;

/** The subset of product-service's product representation this service relies on. */
public record CatalogProduct(Long id, String sku, String name, BigDecimal price, int stockQuantity, boolean active) {
}
