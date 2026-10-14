# 2. Transactional outbox with an in-process relay

**Status:** accepted

## Context

v1 saved the order and then called `kafkaTemplate.send(...)` in the same method. Those are two systems that can't
commit atomically:

* If Kafka is down, the order is saved but no event is ever sent, so the saga never starts.
* `send` is asynchronous. If the database transaction rolls back after the send, consumers act on an order that doesn't exist.

## Decision

Services never publish to Kafka directly. `OutboxWriter.send()` inserts a row into `outbox_messages` **inside the
caller's transaction**, and refuses to run without one. The message commits or rolls back with the state change it
announces. `OutboxRelay` then moves committed rows to Kafka:

* **Push, with polling as a safety net.** An after-commit hook wakes the relay immediately, so messages normally leave
  within milliseconds. A slow poll (1 s) catches anything missed, for example rows committed just before a restart.
* **One relay at a time.** Each batch runs under a transaction-scoped Postgres advisory lock
  (`pg_try_advisory_xact_lock`). With several replicas, exactly one publishes at any moment. That preserves order
  without a leader-election component.
* **At-least-once, in order per partition.** Rows are read in insertion order and sent with an idempotent producer
  (`acks=all`, `enable.idempotence=true`). Within one pass only the oldest unpublished row of each (topic, key) is
  sent; the next goes in a later pass, after the first was acknowledged. A failed send therefore can't let a later
  message for the same order overtake it, and rows of other keys that were acknowledged are still marked
  published (nothing is re-sent needlessly). A row is marked published only after Kafka acknowledged it, and
  anything else is retried (consumers de-duplicate, see ADR 3).
* **Bounded and self-protecting.** The first send that fails synchronously (broker metadata unavailable) ends the
  pass, so the transaction and lock aren't held for `max.block.ms` per row. A row that fails 20 times is *parked*,
  together with later rows of the same topic and key, so one poison message can't stall the whole outbox;
  `outbox.messages.parked` alerts.
* **Trace context survives the hop.** The current W3C `traceparent` is stored on the row and copied onto the Kafka
  record, so the consumer's span continues the original trace.
* Published rows are purged after three days; `outbox.messages.pending` and `outbox.oldest.pending.age` are exported,
  and an alert fires if the oldest unpublished row is over 30 s old.

## Consequences

* "Order saved, event lost" and "event for a rolled-back order" are impossible by construction (tested in
  `OutboxInboxIntegrationTest`).
* Delivery is at-least-once, so every consumer must be idempotent. That's ADR 3.
* One extra insert per message, and a few milliseconds of relay latency.

## Alternatives considered

* **Kafka transactions plus a chained transaction manager.** Not truly atomic across two resources (best-effort 1PC),
  and it ties the database transaction to Kafka's availability.
* **Change data capture with Debezium.** Lower database load at very high volume and no polling, but another moving
  part to run. It is the natural next step if throughput demands it; the outbox table format stays the same.
