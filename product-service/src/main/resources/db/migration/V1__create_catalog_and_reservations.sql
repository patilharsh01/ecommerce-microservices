CREATE TABLE products
(
    id             BIGSERIAL PRIMARY KEY,
    sku            VARCHAR(64)    NOT NULL UNIQUE,
    name           VARCHAR(200)   NOT NULL,
    description    VARCHAR(2000),
    price          NUMERIC(12, 2) NOT NULL CHECK (price > 0),
    -- The database itself refuses to oversell, whatever the application does.
    stock_quantity INT            NOT NULL CHECK (stock_quantity >= 0),
    active         BOOLEAN        NOT NULL DEFAULT TRUE,
    version        BIGINT         NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ    NOT NULL,
    updated_at     TIMESTAMPTZ    NOT NULL
);

CREATE INDEX ix_products_active ON products (active, id);

CREATE TABLE stock_reservations
(
    order_id   BIGINT PRIMARY KEY,
    status     VARCHAR(20) NOT NULL CHECK (status IN ('RESERVED', 'REJECTED', 'RELEASED')),
    reason     VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE stock_reservation_lines
(
    order_id   BIGINT NOT NULL REFERENCES stock_reservations (order_id),
    product_id BIGINT NOT NULL REFERENCES products (id),
    quantity   INT    NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (order_id, product_id)
);
