-- When the customer's personal data and the model file were removed (retention sweep or a
-- deletion request). The order's numbers stay as the business record.

ALTER TABLE orders
    ADD COLUMN erased_at TIMESTAMPTZ;

CREATE INDEX orders_retention_idx ON orders (delete_after)
    WHERE delete_after IS NOT NULL AND erased_at IS NULL;
