package com.harshalpatil.ecommerce.order.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PlaceOrderRequest(
        @NotEmpty(message = "an order needs at least one item") @Size(max = 50) @Valid List<Item> items) {

    public record Item(@NotNull Long productId, @NotNull @Min(1) @Max(OrderLimits.MAX_UNITS_PER_PRODUCT) Integer quantity) {
    }
}
