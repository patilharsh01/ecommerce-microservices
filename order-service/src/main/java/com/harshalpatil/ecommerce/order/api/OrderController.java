package com.harshalpatil.ecommerce.order.api;

import com.harshalpatil.ecommerce.order.domain.OrderStatus;
import com.harshalpatil.ecommerce.platform.web.AuthenticatedUser;
import com.harshalpatil.ecommerce.platform.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Tag(name = "Orders")
public class OrderController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private final OrderService orderService;

    @PostMapping
    @Operation(summary = "Place an order and start its saga",
            description = "Returns 201 for a new order. Repeating the request with the same Idempotency-Key "
                    + "returns 200 with the original order and the header Idempotent-Replayed: true.")
    public ResponseEntity<OrderResponse> place(
            @Parameter(description = "Client-generated unique key (a UUID) that makes retries safe")
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) @Pattern(regexp = "[A-Za-z0-9_-]{8,100}") String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request,
            AuthenticatedUser customer) {
        OrderService.Placement placement = orderService.place(customer, idempotencyKey, request);
        if (placement.replayed()) {
            return ResponseEntity.ok().header(IDEMPOTENT_REPLAYED, "true").body(placement.order());
        }
        return ResponseEntity.created(URI.create("/api/orders/" + placement.order().id())).body(placement.order());
    }

    @GetMapping
    @Operation(summary = "The caller's orders, newest first. Admins may pass scope=all.")
    public PageResponse<OrderSummaryResponse> list(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "mine") @Pattern(regexp = "mine|all") String scope,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable,
            AuthenticatedUser user) {
        return orderService.list(user, "all".equals(scope), status, pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One order with its line items and saga timeline")
    public OrderResponse get(@PathVariable Long id, AuthenticatedUser user) {
        return orderService.get(id, user);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an order whose saga is still running; compensations are triggered")
    public OrderResponse cancel(@PathVariable Long id, AuthenticatedUser user) {
        return orderService.cancel(id, user);
    }

    @GetMapping("/stats")
    @Operation(summary = "Order counts and saga timings for the admin dashboard")
    public OrderStatsResponse stats(AuthenticatedUser user) {
        return orderService.stats(user);
    }
}
