package com.harshalpatil.ecommerce.order.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * <pre>
 *   PENDING ──stock reserved──▶ AWAITING_PAYMENT ──payment completed──▶ CONFIRMED
 *      │                              │
 *      │ stock rejected               │ payment declined / timeout / customer cancel
 *      ▼                              ▼
 *   CANCELLED ◀──all compensations done── COMPENSATING ◀── timeout / customer cancel (from PENDING)
 * </pre>
 */
public enum OrderStatus {

    /** Placed; waiting for inventory to hold the stock. */
    PENDING,
    /** Stock held; waiting for the payment outcome. */
    AWAITING_PAYMENT,
    /** Stock held and paid for. Terminal. */
    CONFIRMED,
    /** Being cancelled: compensating commands sent, waiting for every participant to confirm. */
    COMPENSATING,
    /** Nothing held, nothing charged. Terminal. */
    CANCELLED;

    /** States a running saga step can time out in (and a customer can cancel from). */
    public static final Set<OrderStatus> IN_FLIGHT = EnumSet.of(PENDING, AWAITING_PAYMENT);

    /** States with a deadline: forward steps time out, unconfirmed compensations are re-sent. */
    public static final Set<OrderStatus> WITH_DEADLINE = EnumSet.of(PENDING, AWAITING_PAYMENT, COMPENSATING);

    public boolean isTerminal() {
        return this == CONFIRMED || this == CANCELLED;
    }
}
