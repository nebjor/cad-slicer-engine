-- Server-side cart of a signed-in customer (ADR 0010), so every device sees the same lines.
-- Each line owns a copy of its model file on the order-files volume (file_key); the quote the
-- frontend holds for the line is kept as opaque JSON so the quote schema can change freely.
-- Lines go with the account (cascade; the application deletes the files first) and after
-- account.cart-retention-days without a change.

CREATE TABLE account_cart_items (
    id             UUID         PRIMARY KEY,
    account_id     UUID         NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    -- 1-based order of the lines in the cart.
    position       INT          NOT NULL,
    file_key       TEXT         NOT NULL,
    file_name      TEXT         NOT NULL,
    size_bytes     BIGINT       NOT NULL,
    content_sha256 TEXT         NOT NULL,
    filament       TEXT         NOT NULL,
    color_id       TEXT         NOT NULL,
    quality        TEXT         NOT NULL,
    quantity       INT          NOT NULL,
    quote_json     JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    -- Deferred, so a reorder can rewrite positions within one transaction.
    CONSTRAINT account_cart_items_position_key UNIQUE (account_id, position) DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX account_cart_items_updated_at_idx ON account_cart_items (updated_at);
