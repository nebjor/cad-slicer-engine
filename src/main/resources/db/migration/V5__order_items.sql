-- An order holds one or more items, each its own model with its own slice and line price.
-- Everything that described the single model on the orders row moves to order_items; the
-- order keeps the customer, the state and the order-level money (items total, shipping, VAT,
-- total). Existing orders become one item at position 1.

CREATE TABLE order_items (
    id                     UUID           PRIMARY KEY,
    order_id               UUID           NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    position               INTEGER        NOT NULL,

    original_file_name     VARCHAR(255)   NOT NULL,
    file_key               VARCHAR(64)    NOT NULL,
    file_sha256            VARCHAR(64)    NOT NULL,
    file_size_bytes        BIGINT         NOT NULL,

    filament               VARCHAR(16)    NOT NULL,
    color_code             VARCHAR(32)    NOT NULL,
    color_label            VARCHAR(64)    NOT NULL,
    -- Swatch and material price were not recorded before this migration; null on old orders.
    color_hex              VARCHAR(16),
    color_price_per_kg_chf NUMERIC(10, 2),
    quality                VARCHAR(16)    NOT NULL,
    quantity               INTEGER        NOT NULL,
    machine_profile        VARCHAR(120)   NOT NULL,
    layer_height_mm        NUMERIC(4, 2)  NOT NULL,
    plate_count            INTEGER        NOT NULL,
    print_time_seconds     INTEGER        NOT NULL,
    filament_grams         NUMERIC(10, 1) NOT NULL,

    setup_fee_chf          NUMERIC(10, 2) NOT NULL,
    unit_price_chf         NUMERIC(10, 2) NOT NULL,
    line_total_chf         NUMERIC(10, 2) NOT NULL,

    CONSTRAINT order_items_position_uq UNIQUE (order_id, position)
);

INSERT INTO order_items (id, order_id, position,
                         original_file_name, file_key, file_sha256, file_size_bytes,
                         filament, color_code, color_label, quality, quantity,
                         machine_profile, layer_height_mm, plate_count, print_time_seconds, filament_grams,
                         setup_fee_chf, unit_price_chf, line_total_chf)
SELECT gen_random_uuid(), id, 1,
       original_file_name, file_key, file_sha256, file_size_bytes,
       filament, color_code, color_label, quality, quantity,
       machine_profile, layer_height_mm, plate_count, total_print_time_seconds, total_filament_grams,
       setup_fee, unit_price, items_total
FROM orders;

ALTER TABLE orders
    DROP COLUMN original_file_name,
    DROP COLUMN file_key,
    DROP COLUMN file_sha256,
    DROP COLUMN file_size_bytes,
    DROP COLUMN filament,
    DROP COLUMN color_code,
    DROP COLUMN color_label,
    DROP COLUMN quality,
    DROP COLUMN quantity,
    DROP COLUMN machine_profile,
    DROP COLUMN layer_height_mm,
    DROP COLUMN plate_count,
    DROP COLUMN total_print_time_seconds,
    DROP COLUMN total_filament_grams,
    DROP COLUMN setup_fee,
    DROP COLUMN unit_price;
