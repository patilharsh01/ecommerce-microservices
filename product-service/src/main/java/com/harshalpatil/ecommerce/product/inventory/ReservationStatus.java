package com.harshalpatil.ecommerce.product.inventory;

public enum ReservationStatus {
    /** Stock for every line is held for the order. */
    RESERVED,
    /** At least one line couldn't be supplied; nothing was held. */
    REJECTED,
    /** Held stock went back on the shelf — or the order was cancelled before anything was held (tombstone). */
    RELEASED
}
