-- Payment session attached to an order when it is placed. The provider is "mock" until the
-- Stripe account is live; the session id is what a webhook or the mock confirmation refers to.

ALTER TABLE orders
    ADD COLUMN payment_provider   VARCHAR(20),
    ADD COLUMN payment_session_id VARCHAR(255),
    ADD COLUMN payment_url        VARCHAR(2000);

CREATE UNIQUE INDEX orders_payment_session_idx ON orders (payment_session_id)
    WHERE payment_session_id IS NOT NULL;
