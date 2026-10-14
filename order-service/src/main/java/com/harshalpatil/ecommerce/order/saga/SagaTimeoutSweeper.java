package com.harshalpatil.ecommerce.order.saga;

import com.harshalpatil.ecommerce.order.domain.CancellationCause;
import com.harshalpatil.ecommerce.order.domain.Order;
import com.harshalpatil.ecommerce.order.domain.OrderRepository;
import com.harshalpatil.ecommerce.order.domain.OrderStatus;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * A participant that never answers (crashed, stuck behind a poison message, slow gateway)
 * must not leave stock held forever. Orders whose current step overran its deadline are
 * cancelled and compensated. Compensations that weren't confirmed in time (a command or reply
 * may have been dead-lettered) are sent again until they are. Safe to run on every replica:
 * rows are claimed with {@code FOR UPDATE SKIP LOCKED}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class SagaTimeoutSweeper {

    private final OrderRepository orders;
    private final OrderSagaOrchestrator orchestrator;
    private final SagaProperties properties;
    private final MeterRegistry meterRegistry;

    @Scheduled(fixedDelayString = "${order.saga.sweep-interval:5s}", initialDelayString = "${order.saga.sweep-interval:5s}")
    @Transactional
    public void cancelTimedOutOrders() {
        Instant now = Instant.now();
        List<Long> claimed = orders.lockTimedOut(now, properties.sweepBatch());
        for (Order order : orders.findAllById(claimed)) {
            if (!order.isTimedOut(now)) {
                continue;
            }
            OrderStatus stuckIn = order.getStatus();
            if (stuckIn == OrderStatus.COMPENSATING) {
                orchestrator.retryCompensation(order);
                continue;
            }
            orchestrator.cancel(order, CancellationCause.TIMED_OUT, "No answer within "
                    + properties.stepTimeout().toSeconds() + "s while " + stuckIn.name().toLowerCase().replace('_', ' '));
            meterRegistry.counter("order.saga.timeouts", "status", stuckIn.name()).increment();
        }
        if (!claimed.isEmpty()) {
            log.warn("Handled {} overdue order(s)", claimed.size());
        }
    }
}
