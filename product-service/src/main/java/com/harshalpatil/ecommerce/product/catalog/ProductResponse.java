package com.harshalpatil.ecommerce.product.catalog;

import java.math.BigDecimal;

public record ProductResponse(Long id, String sku, String name, String description, BigDecimal price,
                              int stockQuantity, boolean active, long version) {

    static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getSku(), product.getName(), product.getDescription(),
                product.getPrice(), product.getStockQuantity(), product.isActive(), product.getVersion());
    }
}
