# 3. Idempotent consumers with an inbox table

**Status:** accepted

## Context

Kafka delivers at least once. A consumer can process a record, commit its database transaction and crash before
committing the offset; after a restart or rebalance, the record comes back. The outbox (ADR 2) can also send a message
twice. In v1 a redelivered `OrderCreated` reserved stock twice.

## Decision

Every message carries a `messageId` (a UUID, also sent as a header). `InboxProcessor.process()` runs the handler in one
transaction that starts with:

```sql
INSERT INTO inbox_messages (message_id, consumer, ...) VALUES (?, ?, ...) ON CONFLICT DO NOTHING
```

If no row was inserted, the message was already processed: it is skipped and counted (`inbox.messages.duplicate`).
Otherwise the handler runs, and its side effects, its outgoing messages (through the outbox) and the inbox row commit
together.

* A handler that fails leaves no inbox row, so the retry starts clean.
* Two consumers racing on the same message (possible during a rebalance) serialise on the primary key; the second one
  sees the conflict once the first commits.
* Rows are kept for 14 days, comfortably longer than any redelivery window.

Handlers are also **idempotent by design** wherever cheap: inventory and payments key their records by order id and
answer a repeated command with the original outcome. The inbox is the guarantee; this is defence in depth.

**Slow external calls** (the payment gateway) run *before* the transaction opens, so no database connection is held
while waiting. A cheap read-only check skips duplicates first. If the service crashes between the call and the commit,
the redelivered command calls the gateway again with the same idempotency key (`order-<id>`), which the provider
answers with the original result. Refunds carry their own key (`refund-order-<id>`), so a refund whose commit failed
is never paid out twice. If saving a charge's outcome fails for good (the command is dead-lettered), the later
compensation finds no payment record and asks the gateway to void whatever was charged under `order-<id>` before
writing its tombstone. A charge we never recorded is still given back.

## Failure handling around the consumer

A handler that keeps failing is retried with exponential back-off (0.5, 1, 2, 4 s) and then published to `<topic>.DLT`
with the exception attached, so one bad message can't block its partition. Messages that can't even be decoded skip the
retries. Message types are resolved only from the contracts package, so a hostile header can't choose which class gets
deserialised.

## Consequences

* Effectively-once processing: Kafka still redelivers, but the effects happen once.
* One small insert per consumed message per consumer.
