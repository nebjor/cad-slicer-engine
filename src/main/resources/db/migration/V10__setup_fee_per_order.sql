-- The setup fee is charged once per order instead of once per item (ADR 0009). The order
-- takes over the fees its items carried, so every existing order keeps exactly its total;
-- each line becomes unit price times quantity.

ALTER TABLE orders
    ADD COLUMN setup_fee NUMERIC(10, 2) NOT NULL DEFAULT 0;

UPDATE orders o
SET setup_fee = COALESCE((SELECT SUM(i.setup_fee_chf) FROM order_items i WHERE i.order_id = o.id), 0);

-- items_total held the lines including their fees; the fees move to setup_fee, the total stays.
UPDATE orders
SET items_total = items_total - setup_fee;

UPDATE order_items
SET line_total_chf = unit_price_chf * quantity;

ALTER TABLE order_items
    DROP COLUMN setup_fee_chf;
