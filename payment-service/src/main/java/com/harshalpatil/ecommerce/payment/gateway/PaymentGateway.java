package com.harshalpatil.ecommerce.payment.gateway;

import java.math.BigDecimal;

/**
 * Port to the card processor (Razorpay, Stripe, ...). Every call carries an idempotency key, and
 * real providers answer a repeated key with the original result instead of acting twice. That is
 * what makes it safe to call the gateway again whenever a Kafka command is redelivered.
 */
public interface PaymentGateway {

    /** Charges once per key; repeating the key returns the first outcome. */
    ChargeResult charge(String idempotencyKey, Long customerId, BigDecimal amount);

    /** Refunds a captured charge once per key. */
    void refund(String paymentReference, BigDecimal amount, String idempotencyKey);

    /**
     * Cancels whatever was charged under {@code chargeKey}, for when we have no record of the
     * charge ourselves (it may have succeeded right before we failed to save the outcome).
     *
     * @return the amount given back, zero if nothing had been charged
     */
    BigDecimal voidCharge(String chargeKey, String idempotencyKey);

    record ChargeResult(boolean approved, String reference, String declineReason) {

        public static ChargeResult approved(String reference) {
            return new ChargeResult(true, reference, null);
        }

        public static ChargeResult declined(String reason) {
            return new ChargeResult(false, null, reason);
        }
    }
}
