package com.harshalpatil.ecommerce.order.api;

import com.harshalpatil.ecommerce.order.domain.CancellationCause;
import com.harshalpatil.ecommerce.order.domain.OrderStatus;

import java.util.Map;

/**
 * @param byStatus           current number of orders in each state
 * @param avgSagaSeconds     mean placement-to-outcome time of sagas finished in the last 24h
 * @param p95SagaSeconds     95th percentile of the same
 */
public record OrderStatsResponse(
        Map<OrderStatus, Long> byStatus,
        long confirmedLast24h,
        long cancelledLast24h,
        Map<CancellationCause, Long> cancellationsByCauseLast24h,
        Double avgSagaSeconds,
        Double p95SagaSeconds) {
}
