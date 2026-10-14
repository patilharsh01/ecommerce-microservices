-- Idempotent consumer: one row per (message, consumer) processed, inserted in the same
-- transaction as the handler's side effects. A redelivered message hits the primary key.
CREATE TABLE inbox_messages
(
    message_id   UUID         NOT NULL,
    consumer     VARCHAR(100) NOT NULL,
    message_type VARCHAR(255) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (message_id, consumer)
);

CREATE INDEX ix_inbox_messages_processed_at ON inbox_messages (processed_at);
