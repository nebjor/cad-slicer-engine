-- Orders: a quote turned into a purchase. The price breakdown is copied in full so a later
-- rate change never alters an issued order. The model file lives in the order-files volume
-- under file_key; the client's filename is kept for display only.

CREATE SEQUENCE order_number_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE orders (
    id                       UUID          PRIMARY KEY,
    order_number             VARCHAR(20)   NOT NULL UNIQUE,
    status                   VARCHAR(20)   NOT NULL,
    created_at               TIMESTAMPTZ   NOT NULL,
    quote_expires_at         TIMESTAMPTZ   NOT NULL,
    paid_at                  TIMESTAMPTZ,
    shipped_at               TIMESTAMPTZ,
    delete_after             TIMESTAMPTZ,

    customer_name            VARCHAR(120)  NOT NULL,
    customer_email           VARCHAR(254)  NOT NULL,
    street                   VARCHAR(160)  NOT NULL,
    postal_code              VARCHAR(16)   NOT NULL,
    city                     VARCHAR(120)  NOT NULL,
    country                  VARCHAR(2)    NOT NULL,

    original_file_name       VARCHAR(255)  NOT NULL,
    file_key                 VARCHAR(64)   NOT NULL,
    file_sha256              VARCHAR(64)   NOT NULL,
    file_size_bytes          BIGINT        NOT NULL,

    filament                 VARCHAR(16)   NOT NULL,
    color_code               VARCHAR(32)   NOT NULL,
    color_label              VARCHAR(64)   NOT NULL,
    quality                  VARCHAR(16)   NOT NULL,
    quantity                 INTEGER       NOT NULL,
    machine_profile          VARCHAR(120)  NOT NULL,
    layer_height_mm          NUMERIC(4, 2) NOT NULL,
    plate_count              INTEGER       NOT NULL,
    total_print_time_seconds INTEGER       NOT NULL,
    total_filament_grams     NUMERIC(10, 1) NOT NULL,

    currency                 VARCHAR(3)    NOT NULL,
    setup_fee                NUMERIC(10, 2) NOT NULL,
    unit_price               NUMERIC(10, 2) NOT NULL,
    items_total              NUMERIC(10, 2) NOT NULL,
    shipping                 NUMERIC(10, 2) NOT NULL,
    vat_rate                 NUMERIC(5, 4)  NOT NULL,
    vat_amount               NUMERIC(10, 2) NOT NULL,
    total                    NUMERIC(10, 2) NOT NULL
);

CREATE INDEX orders_status_idx ON orders (status);
CREATE INDEX orders_delete_after_idx ON orders (delete_after);
CREATE INDEX orders_customer_email_idx ON orders (customer_email);
