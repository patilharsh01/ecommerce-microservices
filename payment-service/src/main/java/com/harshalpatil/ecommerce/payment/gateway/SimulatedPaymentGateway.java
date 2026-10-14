package com.harshalpatil.ecommerce.payment.gateway;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stand-in for a real card processor. It behaves like one where it matters for correctness:
 * charges, refunds and voids are idempotent per key, so a retried command never charges or
 * refunds twice. Its decisions are switchable at runtime through
 * {@code PUT /api/payments/simulation} so the dashboard can demonstrate declines and timeouts.
 * State lives in memory per instance — fine for a demo, the provider's job in production.
 */
@Component
@Slf4j
public class SimulatedPaymentGateway implements PaymentGateway {

    private final AtomicReference<SimulationSettings> settings;
    private final Timer latency;
    private final Map<String, Charge> charges = new ConcurrentHashMap<>();
    private final Set<String> completedRefunds = ConcurrentHashMap.newKeySet();

    public SimulatedPaymentGateway(SimulationSettings initialSettings, MeterRegistry meterRegistry) {
        this.settings = new AtomicReference<>(initialSettings);
        this.latency = Timer.builder("payments.gateway.latency")
                .description("Time spent waiting for the payment gateway")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    @Override
    public ChargeResult charge(String idempotencyKey, Long customerId, BigDecimal amount) {
        Charge existing = charges.get(idempotencyKey);
        if (existing != null) {
            log.info("Repeated charge key {}; returning the original outcome", idempotencyKey);
            return existing.result();
        }
        return latency.record(() -> {
            SimulationSettings current = settings.get();
            simulateLatency(current.latencyMs());
            ChargeResult result = switch (current.mode()) {
                case APPROVE_ALL -> approve();
                case DECLINE_ALL -> ChargeResult.declined("Card declined by issuer");
                case APPROVE_UNDER_LIMIT -> amount.compareTo(current.approvalLimit()) <= 0
                        ? approve()
                        : ChargeResult.declined("Amount " + amount.toPlainString() + " exceeds the card limit of "
                        + current.approvalLimit().toPlainString());
            };
            return charges.computeIfAbsent(idempotencyKey, key -> new Charge(result, amount)).result();
        });
    }

    @Override
    public void refund(String paymentReference, BigDecimal amount, String idempotencyKey) {
        if (!completedRefunds.add(idempotencyKey)) {
            log.info("Repeated refund key {}; already refunded", idempotencyKey);
            return;
        }
        charges.values().stream()
                .filter(charge -> paymentReference.equals(charge.result().reference()))
                .forEach(Charge::markRefunded);
        log.info("Refunded {} on {}", amount.toPlainString(), paymentReference);
    }

    @Override
    public BigDecimal voidCharge(String chargeKey, String idempotencyKey) {
        Charge charge = charges.get(chargeKey);
        if (charge == null || !charge.result().approved() || !completedRefunds.add(idempotencyKey)
                || !charge.markRefunded()) {
            return BigDecimal.ZERO;
        }
        log.warn("Voided charge {} ({}) that had no payment record", chargeKey, charge.result().reference());
        return charge.amount();
    }

    public SimulationSettings settings() {
        return settings.get();
    }

    public SimulationSettings update(SimulationSettings newSettings) {
        settings.set(newSettings);
        log.warn("Payment simulation changed to {}", newSettings);
        return newSettings;
    }

    private static ChargeResult approve() {
        return ChargeResult.approved("PAY-" + UUID.randomUUID().toString().substring(0, 13).toUpperCase());
    }

    private static void simulateLatency(int millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the payment gateway", ex);
        }
    }

    private static final class Charge {

        private final ChargeResult result;
        private final BigDecimal amount;
        private boolean refunded;

        private Charge(ChargeResult result, BigDecimal amount) {
            this.result = result;
            this.amount = amount;
        }

        ChargeResult result() {
            return result;
        }

        BigDecimal amount() {
            return amount;
        }

        /** @return true if this call did the refunding */
        synchronized boolean markRefunded() {
            if (refunded) {
                return false;
            }
            refunded = true;
            return true;
        }
    }
}
