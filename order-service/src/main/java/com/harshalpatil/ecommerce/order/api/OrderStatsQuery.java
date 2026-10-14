package com.harshalpatil.ecommerce.order.api;

import com.harshalpatil.ecommerce.order.domain.CancellationCause;
import com.harshalpatil.ecommerce.order.domain.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/** Reporting query for the admin dashboard; plain SQL because it's aggregation, not domain logic. */
@Component
@RequiredArgsConstructor
class OrderStatsQuery {

    private final JdbcTemplate jdbc;

    OrderStatsResponse load() {
        Map<OrderStatus, Long> byStatus = new EnumMap<>(OrderStatus.class);
        for (OrderStatus status : OrderStatus.values()) {
            byStatus.put(status, 0L);
        }
        jdbc.query("SELECT status, count(*) AS total FROM orders GROUP BY status",
                rs -> {
                    byStatus.put(OrderStatus.valueOf(rs.getString("status")), rs.getLong("total"));
                });

        Map<CancellationCause, Long> byCause = new EnumMap<>(CancellationCause.class);
        jdbc.query("""
                SELECT cancellation_cause, count(*) AS total FROM orders
                WHERE status = 'CANCELLED' AND completed_at >= now() - interval '24 hours'
                GROUP BY cancellation_cause
                """, rs -> {
            byCause.put(CancellationCause.valueOf(rs.getString("cancellation_cause")), rs.getLong("total"));
        });

        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE status = 'CONFIRMED') AS confirmed,
                       count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled,
                       avg(EXTRACT(EPOCH FROM completed_at - created_at))::float8 AS avg_seconds,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM completed_at - created_at))::float8 AS p95_seconds
                FROM orders
                WHERE completed_at >= now() - interval '24 hours'
                """, (rs, row) -> new OrderStatsResponse(byStatus, rs.getLong("confirmed"), rs.getLong("cancelled"), byCause,
                rs.getObject("avg_seconds", Double.class), rs.getObject("p95_seconds", Double.class)));
    }
}
