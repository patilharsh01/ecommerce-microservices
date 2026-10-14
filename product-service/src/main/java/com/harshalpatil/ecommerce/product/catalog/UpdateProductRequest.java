package com.harshalpatil.ecommerce.product.catalog;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Full replacement of the editable fields. {@code version} must be the version the client
 * last read: if stock or price changed in the meantime the update is rejected with 409
 * rather than silently overwriting it.
 */
public record UpdateProductRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 2000) String description,
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal price,
        @NotNull @Min(0) @Max(1_000_000) Integer stockQuantity,
        @NotNull Boolean active,
        @NotNull Long version) {
}
