# 6. Saga timeouts and late replies

**Status:** accepted

## Context

A participant may never answer: it crashed, it's stuck behind a dead-lettered message, or its payment gateway hangs.
Without a deadline the order stays `AWAITING_PAYMENT` forever with stock held. With a deadline, a second problem
appears: the answer can still arrive *after* the orchestrator has given up.

## Decision

**Deadlines.** Entering `PENDING` or `AWAITING_PAYMENT` sets `deadline_at = now + step timeout` (30 s by default).
`SagaTimeoutSweeper` runs every 5 s, claims overdue orders with `FOR UPDATE SKIP LOCKED` (safe on every replica) and
cancels them through the normal compensation path:

| Stuck in | Compensations sent |
|---|---|
| `PENDING` | `ReleaseStock` |
| `AWAITING_PAYMENT` | `ReleaseStock` and `RefundPayment` |

The order stays `COMPENSATING` until **every** compensation is confirmed (`awaiting_stock_release`, `awaiting_refund`),
and only then becomes `CANCELLED` and emits `OrderCancelled`.

**Compensations have a deadline too.** A compensating command or its reply can be dead-lettered (for example during a
database outage longer than the consumer's retry budget). Waiting would then leave the order compensating forever, so
when the deadline passes the sweeper sends the *outstanding* compensations again (`COMPENSATION_RETRIED` on the
timeline). That is always safe: inventory answers a repeated release with "nothing held" and payments answer a
repeated refund with "nothing to refund", and the order ignores whichever reply arrives second.

**Late replies are safe to ignore** because of ordering: a compensating command goes to the same topic and partition
(key = order id) as the command it undoes, *after* it. Inventory always sees `ReserveStock` before `ReleaseStock`, and
payments always see `ProcessPayment` before `RefundPayment`. So when a late `PaymentCompleted` reaches an order that's
already compensating, the refund is already queued behind that charge. The orchestrator records `LATE_REPLY_IGNORED` on
the timeline and moves on.

**Tombstones** cover the case where ordering can't be relied on: a `ReleaseStock` with no reservation, or a
`RefundPayment` with no payment, records a `RELEASED` or `VOIDED` row. A `ReserveStock` or `ProcessPayment` arriving
later for that order is refused instead of executed.

Optimistic locking on the order row resolves the remaining race, a reply arriving while the sweeper is cancelling: one
transaction wins and the other rolls back and is retried against the new state.

## Consequences

* No order holds stock or money indefinitely. If a participant stays down, orders wait in `COMPENSATING` and the
  compensations are re-sent each step timeout. The `SagaStuckCompensating` alert fires after five minutes, and the
  compensation completes as soon as the participant processes any of the commands.
* The step timeout is a business decision: too short cancels orders that would have succeeded, too long holds stock.
  It is configurable (`ORDER_SAGA_STEP_TIMEOUT`).
* Covered end to end by `OrderSagaIntegrationTest.stalledPaymentTimesOutAndTheLateChargeIsRefunded` and by the
  e2e script, and demonstrable from the UI's failure lab.
