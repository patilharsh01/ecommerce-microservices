# 1. Orchestrated saga instead of choreography

**Status:** accepted (replaces the choreography of v1)

## Context

Placing an order touches three services that each own their data: orders, inventory and payments. A distributed
transaction (2PC/XA) across Postgres databases and Kafka isn't available, and wouldn't be desirable, so the order is a
saga: a sequence of local transactions, each with a compensating action.

Version 1 used choreography: each service reacted to the previous service's event. It worked for the happy path and
broke down at the edges:

* **No compensation.** When payment failed, nobody released the reserved stock. Fixing that meant inventory had to know
  about payment events, coupling the participants to each other.
* **Status could go backwards.** Order status was set from events arriving on four different topics. Kafka orders
  messages only within a partition of one topic, so `PaymentCompleted` could be applied before `StockReserved` and the
  order would end up `STOCK_RESERVED` after it was confirmed.
* **No owner for "stuck".** If a participant never answered, no service was responsible for noticing.

## Decision

order-service **orchestrates** the saga:

* It sends commands to a participant-specific topic (`inventory.commands`, `payment.commands`).
* Participants answer on **one** replies topic, `order.saga.replies`, keyed by order id, so the replies for an order
  arrive in order.
* The order row is the saga state: an explicit state machine (`PENDING → AWAITING_PAYMENT → CONFIRMED`, or
  `→ COMPENSATING → CANCELLED`) that rejects transitions from the wrong state.
* Every decision is appended to an `order_timeline` table, which the UI shows as a swim-lane diagram.

## Consequences

* The whole flow, including every compensation, is in one class (`OrderSagaOrchestrator`) and unit-tested branch by branch.
* Participants know nothing about each other or about the saga's shape; they just execute commands idempotently.
* The orchestrator is a single logical owner, but not a single point of failure: it is stateless apart from Postgres,
  scales horizontally like any consumer group, and the timeout sweeper uses `FOR UPDATE SKIP LOCKED` so replicas never
  fight over the same order.
* Adding a step (say, shipping) means adding a state and a reply handler in one service instead of rewiring several.

## Alternatives considered

* **Keep choreography and add compensations.** Each new failure path would spread across services, and the ordering
  problem would remain.
* **A workflow engine (Temporal, Camunda).** Excellent for long-running business processes, but a heavy dependency for
  a three-step saga, and it would hide the mechanics this project is meant to demonstrate.
