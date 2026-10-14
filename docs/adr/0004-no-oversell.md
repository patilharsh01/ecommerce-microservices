# 4. Preventing oversell

**Status:** accepted (replaces optimistic locking with retries)

## Context

Many orders can try to reserve the last units of a product at the same moment. Reservations are all-or-nothing across
an order's lines: if the third line can't be supplied, the first two must not stay reserved.

v1 used `@Version` optimistic locking with `@Retryable`. Under contention, most transactions failed and retried; after
three attempts a reservation was dropped (the exception escaped the listener), even though stock might have been
available by then.

## Decision

`InventoryService.reserve()` does, in one transaction:

1. `SELECT ... FOR UPDATE` on **all** products in the order, **sorted by id**
   (`ProductRepository.findAllByIdForUpdate`). Every transaction takes locks in the same global order, so two orders
   for the same pair of products queue up instead of deadlocking.
2. Checks every line against the locked, current stock. On any shortage nothing is decremented; a `REJECTED`
   reservation and a `StockReservationFailed` reply are written.
3. Otherwise decrements every line and records a `RESERVED` reservation with its lines, which is what
   `ReleaseStock` later puts back.

The schema backs this up with `CHECK (stock_quantity >= 0)`, so even a future bug can't oversell.

Admin edits use the product's `@Version` across the HTTP round trip: the client sends the version it loaded, and a
stale update gets `409 STALE_VERSION` instead of overwriting stock that orders changed meanwhile. Restocking uses
relative adjustments, which need no version.

## Consequences

* Correct under any interleaving. `InventoryIntegrationTest` runs 50 concurrent orders for 10 units, half listing the
  products in reverse order: exactly 10 reserved, 40 rejected, stock 0, no deadlock.
* Contended rows are briefly serialised. That is the honest cost of selling a scarce item; a hot product with huge
  traffic would move to sharded counters or a reservation queue.
