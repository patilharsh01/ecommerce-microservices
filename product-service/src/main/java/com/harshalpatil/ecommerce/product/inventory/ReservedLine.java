package com.harshalpatil.ecommerce.product.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public record ReservedLine(
        @Column(name = "product_id", nullable = false) Long productId,
        @Column(nullable = false) int quantity) {
}
