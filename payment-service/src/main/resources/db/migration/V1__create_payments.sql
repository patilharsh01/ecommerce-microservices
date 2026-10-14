CREATE TABLE payments
(
    id             BIGSERIAL PRIMARY KEY,
    -- One payment per order: the database enforces "never charge an order twice".
    order_id       BIGINT      NOT NULL UNIQUE,
    customer_id    BIGINT,
    amount         NUMERIC(12, 2),
    status         VARCHAR(20) NOT NULL CHECK (status IN ('COMPLETED', 'DECLINED', 'REFUNDED', 'VOIDED')),
    reference      VARCHAR(64),
    failure_reason VARCHAR(500),
    refund_reason  VARCHAR(500),
    version        BIGINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    -- Only VOIDED tombstones are created without a charge request behind them.
    CONSTRAINT payments_charge_details CHECK (status = 'VOIDED' OR (customer_id IS NOT NULL AND amount IS NOT NULL)),
    CONSTRAINT payments_completed_has_reference CHECK (status NOT IN ('COMPLETED', 'REFUNDED') OR reference IS NOT NULL)
);

CREATE INDEX ix_payments_customer ON payments (customer_id);
