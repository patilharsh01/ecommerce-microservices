CREATE TABLE orders
(
    id                     BIGSERIAL PRIMARY KEY,
    customer_id            BIGINT         NOT NULL,
    status                 VARCHAR(20)    NOT NULL
        CHECK (status IN ('PENDING', 'AWAITING_PAYMENT', 'CONFIRMED', 'COMPENSATING', 'CANCELLED')),
    total_amount           NUMERIC(12, 2) NOT NULL CHECK (total_amount > 0),
    item_count             INT            NOT NULL CHECK (item_count > 0),
    idempotency_key        VARCHAR(100),
    request_fingerprint    VARCHAR(64),
    payment_reference      VARCHAR(64),
    cancellation_cause     VARCHAR(30),
    cancellation_reason    VARCHAR(500),
    awaiting_stock_release BOOLEAN        NOT NULL DEFAULT FALSE,
    awaiting_refund        BOOLEAN        NOT NULL DEFAULT FALSE,
    deadline_at            TIMESTAMPTZ,
    created_at             TIMESTAMPTZ    NOT NULL,
    updated_at             TIMESTAMPTZ    NOT NULL,
    completed_at           TIMESTAMPTZ,
    version                BIGINT         NOT NULL DEFAULT 0
);

-- Settles concurrent retries of the same request: only one insert can win.
CREATE UNIQUE INDEX ux_orders_customer_idempotency_key ON orders (customer_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX ix_orders_customer ON orders (customer_id, id DESC);
CREATE INDEX ix_orders_status ON orders (status, id DESC);
-- The sweeper only ever looks at sagas that are still running or compensating.
CREATE INDEX ix_orders_deadline ON orders (deadline_at) WHERE status IN ('PENDING', 'AWAITING_PAYMENT', 'COMPENSATING');
CREATE INDEX ix_orders_completed_at ON orders (completed_at) WHERE completed_at IS NOT NULL;

CREATE TABLE order_line_items
(
    id           BIGSERIAL PRIMARY KEY,
    order_id     BIGINT         NOT NULL REFERENCES orders (id),
    product_id   BIGINT         NOT NULL,
    product_name VARCHAR(200)   NOT NULL,
    quantity     INT            NOT NULL CHECK (quantity > 0),
    unit_price   NUMERIC(12, 2) NOT NULL CHECK (unit_price > 0)
);

CREATE INDEX ix_order_line_items_order ON order_line_items (order_id);

CREATE TABLE order_timeline
(
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT      NOT NULL REFERENCES orders (id),
    step        VARCHAR(40) NOT NULL,
    detail      VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_order_timeline_order ON order_timeline (order_id, id);
