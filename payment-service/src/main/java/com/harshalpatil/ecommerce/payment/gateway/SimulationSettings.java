package com.harshalpatil.ecommerce.payment.gateway;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/**
 * Knobs for failure injection in demos and tests.
 *
 * @param mode          how the simulated issuer decides
 * @param approvalLimit in {@link Mode#APPROVE_UNDER_LIMIT}, charges above this amount are declined
 * @param latencyMs     artificial gateway latency; set it above the saga timeout to watch the
 *                      orchestrator time out, compensate, and then refund the late charge
 */
@ConfigurationProperties("payment.simulation")
public record SimulationSettings(
        @NotNull @DefaultValue("APPROVE_UNDER_LIMIT") Mode mode,
        @NotNull @DecimalMin("0.01") @DefaultValue("50000") BigDecimal approvalLimit,
        @Min(0) @Max(60_000) @DefaultValue("0") int latencyMs) {

    public enum Mode {
        APPROVE_UNDER_LIMIT,
        APPROVE_ALL,
        DECLINE_ALL
    }
}
