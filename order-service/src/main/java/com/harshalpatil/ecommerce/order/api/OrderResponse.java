package com.harshalpatil.ecommerce.order.api;

import com.harshalpatil.ecommerce.order.domain.CancellationCause;
import com.harshalpatil.ecommerce.order.domain.Order;
import com.harshalpatil.ecommerce.order.domain.OrderLineItem;
import com.harshalpatil.ecommerce.order.domain.OrderStatus;
import com.harshalpatil.ecommerce.order.domain.SagaStep;
import com.harshalpatil.ecommerce.order.domain.TimelineEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Full order, including the saga timeline the dashboard draws. */
public record OrderResponse(
        Long id,
        Long customerId,
        OrderStatus status,
        BigDecimal totalAmount,
        int itemCount,
        String paymentReference,
        CancellationCause cancellationCause,
        String cancellationReason,
        Instant deadlineAt,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        List<LineItem> items,
        List<Timeline> timeline) {

    public record LineItem(Long productId, String productName, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    public record Timeline(SagaStep step, SagaStep.Participant participant, SagaStep.Kind kind, String detail,
                           Instant occurredAt) {
    }

    static OrderResponse from(Order order) {
        return new OrderResponse(order.getId(), order.getCustomerId(), order.getStatus(), order.getTotalAmount(),
                order.getItemCount(), order.getPaymentReference(), order.getCancellationCause(),
                order.getCancellationReason(), order.getDeadlineAt(), order.getCreatedAt(), order.getUpdatedAt(),
                order.getCompletedAt(),
                order.getItems().stream().map(OrderResponse::lineItem).toList(),
                order.getTimeline().stream().map(OrderResponse::timeline).toList());
    }

    private static LineItem lineItem(OrderLineItem item) {
        return new LineItem(item.getProductId(), item.getProductName(), item.getQuantity(), item.getUnitPrice(),
                item.lineTotal());
    }

    private static Timeline timeline(TimelineEntry entry) {
        return new Timeline(entry.getStep(), entry.getStep().participant(), entry.getStep().kind(), entry.getDetail(),
                entry.getOccurredAt());
    }
}
