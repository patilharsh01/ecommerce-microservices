package com.harshalpatil.ecommerce.payment.payments;

public enum PaymentStatus {
    COMPLETED,
    DECLINED,
    /** Was COMPLETED; the money went back because the order was cancelled afterwards. */
    REFUNDED,
    /** Tombstone: the order was cancelled before it was charged, so it must never be charged. */
    VOIDED
}
