-- Transactional outbox: rows are written in the same transaction as the business change
-- and relayed to Kafka afterwards by OutboxRelay. `id` gives a strict insertion order.
CREATE TABLE outbox_messages
(
    id           BIGSERIAL PRIMARY KEY,
    message_id   UUID         NOT NULL UNIQUE,
    topic        VARCHAR(255) NOT NULL,
    message_key  VARCHAR(255) NOT NULL,
    message_type VARCHAR(255) NOT NULL,
    payload      JSONB        NOT NULL,
    headers      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ,
    attempts     INT          NOT NULL DEFAULT 0,
    last_error   TEXT
);

-- The relay only ever scans unpublished rows; keep that index tiny.
CREATE INDEX ix_outbox_messages_pending ON outbox_messages (id) WHERE published_at IS NULL;
CREATE INDEX ix_outbox_messages_published_at ON outbox_messages (published_at) WHERE published_at IS NOT NULL;
