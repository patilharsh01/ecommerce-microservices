package com.harshalpatil.ecommerce.payment.gateway;

import com.harshalpatil.ecommerce.payment.gateway.PaymentGateway.ChargeResult;
import com.harshalpatil.ecommerce.payment.gateway.SimulationSettings.Mode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedPaymentGatewayTest {

    private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway(
            new SimulationSettings(Mode.APPROVE_UNDER_LIMIT, new BigDecimal("1000"), 0), new SimpleMeterRegistry());

    @Test
    void approvesUpToTheLimitInclusive() {
        ChargeResult result = gateway.charge("order-1", 1L, new BigDecimal("1000.00"));

        assertThat(result.approved()).isTrue();
        assertThat(result.reference()).startsWith("PAY-");
    }

    @Test
    void declinesAboveTheLimitWithAReadableReason() {
        ChargeResult result = gateway.charge("order-2", 1L, new BigDecimal("1000.01"));

        assertThat(result.approved()).isFalse();
        assertThat(result.declineReason()).isEqualTo("Amount 1000.01 exceeds the card limit of 1000");
    }

    @Test
    void modesCanBeSwitchedAtRuntime() {
        gateway.update(new SimulationSettings(Mode.DECLINE_ALL, BigDecimal.ONE, 0));
        assertThat(gateway.charge("order-3", 1L, BigDecimal.ONE).approved()).isFalse();

        gateway.update(new SimulationSettings(Mode.APPROVE_ALL, BigDecimal.ONE, 0));
        assertThat(gateway.charge("order-4", 1L, new BigDecimal("999999")).approved()).isTrue();
    }

    @Test
    void repeatingAChargeKeyReturnsTheFirstOutcomeInsteadOfChargingAgain() {
        ChargeResult first = gateway.charge("order-6", 1L, new BigDecimal("10.00"));
        gateway.update(new SimulationSettings(Mode.DECLINE_ALL, BigDecimal.ONE, 0));

        ChargeResult repeat = gateway.charge("order-6", 1L, new BigDecimal("10.00"));

        assertThat(repeat).isEqualTo(first);
        assertThat(repeat.approved()).isTrue();
    }

    @Test
    void voidingGivesBackAnUnrecordedChargeExactlyOnce() {
        gateway.charge("order-7", 1L, new BigDecimal("40.00"));

        assertThat(gateway.voidCharge("order-7", "refund-order-7")).isEqualByComparingTo("40.00");
        assertThat(gateway.voidCharge("order-7", "refund-order-7")).isZero();
        assertThat(gateway.voidCharge("order-never-charged", "refund-x")).isZero();
    }

    @Test
    void aChargeThatWasRefundedCannotAlsoBeVoided() {
        ChargeResult charge = gateway.charge("order-8", 1L, new BigDecimal("40.00"));
        gateway.refund(charge.reference(), new BigDecimal("40.00"), "refund-order-8");

        assertThat(gateway.voidCharge("order-8", "void-order-8")).isZero();
    }

    @Test
    void injectedLatencyIsHonoured() {
        gateway.update(new SimulationSettings(Mode.APPROVE_ALL, BigDecimal.ONE, 150));

        long started = System.nanoTime();
        gateway.charge("order-5", 1L, BigDecimal.ONE);

        assertThat((System.nanoTime() - started) / 1_000_000).isGreaterThanOrEqualTo(140);
    }
}
