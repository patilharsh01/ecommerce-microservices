package com.harshalpatil.ecommerce.order.saga;

import com.harshalpatil.ecommerce.order.domain.OrderRepository;
import com.harshalpatil.ecommerce.order.domain.OrderStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Number of orders currently in each non-terminal state. A COMPENSATING count that keeps
 * growing means a participant has stopped answering — the Grafana dashboard alerts on it.
 */
@Component
@RequiredArgsConstructor
class SagaMetrics implements MeterBinder {

    private final OrderRepository orders;

    @Override
    public void bindTo(MeterRegistry registry) {
        for (OrderStatus status : new OrderStatus[]{OrderStatus.PENDING, OrderStatus.AWAITING_PAYMENT, OrderStatus.COMPENSATING}) {
            Gauge.builder("orders.in.flight", orders, repository -> repository.countByStatus(status))
                    .description("Orders whose saga is still running, by state")
                    .tag("status", status.name())
                    .register(registry);
        }
    }
}
