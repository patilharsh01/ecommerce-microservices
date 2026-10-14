package com.harshalpatil.ecommerce.product.catalog;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Relative change (a delivery arrived, a unit was damaged) — safe to apply without a version check. */
public record StockAdjustmentRequest(@NotNull @Min(-1_000_000) @Max(1_000_000) Integer delta) {
}
