CREATE TABLE notifications
(
    id          BIGSERIAL PRIMARY KEY,
    customer_id BIGINT        NOT NULL,
    order_id    BIGINT        NOT NULL,
    type        VARCHAR(30)   NOT NULL,
    subject     VARCHAR(200)  NOT NULL,
    body        VARCHAR(1000) NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL
);

CREATE INDEX ix_notifications_customer ON notifications (customer_id, id DESC);
CREATE INDEX ix_notifications_order ON notifications (order_id);
