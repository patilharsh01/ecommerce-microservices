package com.harshalpatil.ecommerce.order.domain;

/**
 * One entry on an order's saga timeline. {@link Participant} says whose lane it belongs to;
 * {@link Kind} says how to read it (a command we sent, a good or bad outcome, an undo step).
 */
public enum SagaStep {

    ORDER_PLACED(Participant.ORDER, Kind.INFO),
    RESERVE_STOCK_SENT(Participant.INVENTORY, Kind.COMMAND),
    STOCK_RESERVED(Participant.INVENTORY, Kind.SUCCESS),
    STOCK_REJECTED(Participant.INVENTORY, Kind.FAILURE),
    PROCESS_PAYMENT_SENT(Participant.PAYMENT, Kind.COMMAND),
    PAYMENT_COMPLETED(Participant.PAYMENT, Kind.SUCCESS),
    PAYMENT_DECLINED(Participant.PAYMENT, Kind.FAILURE),
    COMPENSATION_STARTED(Participant.ORDER, Kind.COMPENSATION),
    RELEASE_STOCK_SENT(Participant.INVENTORY, Kind.COMPENSATION),
    STOCK_RELEASED(Participant.INVENTORY, Kind.COMPENSATION),
    REFUND_PAYMENT_SENT(Participant.PAYMENT, Kind.COMPENSATION),
    PAYMENT_REFUNDED(Participant.PAYMENT, Kind.COMPENSATION),
    COMPENSATION_RETRIED(Participant.ORDER, Kind.COMPENSATION),
    ORDER_CONFIRMED(Participant.ORDER, Kind.SUCCESS),
    ORDER_CANCELLED(Participant.ORDER, Kind.FAILURE),
    LATE_REPLY_IGNORED(Participant.ORDER, Kind.INFO);

    public enum Participant { ORDER, INVENTORY, PAYMENT }

    public enum Kind { INFO, COMMAND, SUCCESS, FAILURE, COMPENSATION }

    private final Participant participant;
    private final Kind kind;

    SagaStep(Participant participant, Kind kind) {
        this.participant = participant;
        this.kind = kind;
    }

    public Participant participant() {
        return participant;
    }

    public Kind kind() {
        return kind;
    }
}
