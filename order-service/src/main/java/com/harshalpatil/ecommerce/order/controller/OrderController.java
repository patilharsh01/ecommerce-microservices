package com.harshalpatil.ecommerce.order.controller;

import com.harshalpatil.ecommerce.order.dto.CreateOrderRequest;
import com.harshalpatil.ecommerce.order.dto.OrderResponse;
import com.harshalpatil.ecommerce.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Every request here has already passed through api-gateway's JWT filter,
 * which resolves the caller's identity and forwards it as X-User-Id.
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> create(@RequestHeader("X-User-Id") Long customerId,
                                                 @Valid @RequestBody CreateOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.createOrder(customerId, request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> findById(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.findById(id));
    }

    @GetMapping
    public ResponseEntity<List<OrderResponse>> myOrders(@RequestHeader("X-User-Id") Long customerId) {
        return ResponseEntity.ok(orderService.findByCustomer(customerId));
    }
}
