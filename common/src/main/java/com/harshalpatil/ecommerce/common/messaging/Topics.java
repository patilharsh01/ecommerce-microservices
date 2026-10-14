package com.harshalpatil.ecommerce.common.messaging;

/**
 * Topic names shared by producers and consumers.
 * <p>
 * Commands flow from the order saga orchestrator to a participant on that participant's
 * command topic; every participant answers on the single replies topic. Keeping one
 * replies topic (keyed by order id) means the orchestrator sees the replies for an order
 * strictly in the order they were sent, which a topic-per-event layout can't guarantee.
 */
public final class Topics {

    public static final String INVENTORY_COMMANDS = "inventory.commands";
    public static final String PAYMENT_COMMANDS = "payment.commands";
    public static final String ORDER_SAGA_REPLIES = "order.saga.replies";
    public static final String ORDER_EVENTS = "order.events";

    /** Suffix of the dead-letter topic that receives records a consumer gave up on. */
    public static final String DEAD_LETTER_SUFFIX = ".DLT";

    private Topics() {
    }
}
