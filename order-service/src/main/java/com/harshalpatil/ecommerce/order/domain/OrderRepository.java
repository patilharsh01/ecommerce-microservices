package com.harshalpatil.ecommerce.order.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByCustomerIdAndIdempotencyKey(Long customerId, String idempotencyKey);

    Page<Order> findByCustomerId(Long customerId, Pageable pageable);

    Page<Order> findByCustomerIdAndStatus(Long customerId, OrderStatus status, Pageable pageable);

    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    long countByStatus(OrderStatus status);

    /**
     * Claims a batch of orders past their deadline: running steps that timed out, and
     * compensations not yet confirmed. {@code SKIP LOCKED}
     * lets several replicas sweep concurrently without blocking on, or double-handling, the
     * same order — each row is claimed by exactly one transaction.
     */
    @Query(value = """
            SELECT id FROM orders
            WHERE status IN ('PENDING', 'AWAITING_PAYMENT', 'COMPENSATING') AND deadline_at < :now
            ORDER BY deadline_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockTimedOut(@Param("now") Instant now, @Param("limit") int limit);
}
