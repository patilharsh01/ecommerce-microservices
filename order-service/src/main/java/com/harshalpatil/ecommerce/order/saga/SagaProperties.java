package com.harshalpatil.ecommerce.order.saga;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param stepTimeout   how long one saga step (stock reservation, then payment) may take before
 *                      the order is cancelled and compensated
 * @param sweepInterval how often the timeout sweeper looks for overdue orders
 * @param sweepBatch    orders claimed per sweeper transaction
 */
@ConfigurationProperties("order.saga")
public record SagaProperties(
        @DefaultValue("30s") Duration stepTimeout,
        @DefaultValue("5s") Duration sweepInterval,
        @DefaultValue("50") int sweepBatch) {
}
