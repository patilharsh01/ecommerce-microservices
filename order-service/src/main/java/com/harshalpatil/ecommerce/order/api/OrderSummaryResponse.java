package com.harshalpatil.ecommerce.order.api;

import com.harshalpatil.ecommerce.order.domain.CancellationCause;
import com.harshalpatil.ecommerce.order.domain.Order;
import com.harshalpatil.ecommerce.order.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** List view: no line items or timeline, so listing never touches the child tables. */
public record OrderSummaryResponse(Long id, Long customerId, OrderStatus status, BigDecimal totalAmount, int itemCount,
                                   CancellationCause cancellationCause, String cancellationReason, Instant createdAt,
                                   Instant completedAt) {

    static OrderSummaryResponse from(Order order) {
        return new OrderSummaryResponse(order.getId(), order.getCustomerId(), order.getStatus(), order.getTotalAmount(),
                order.getItemCount(), order.getCancellationCause(), order.getCancellationReason(), order.getCreatedAt(),
                order.getCompletedAt());
    }
}
