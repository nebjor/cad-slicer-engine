-- Slice calibration from real orders (ADR 0008). Every ordered model, and any model the
-- owner queues by hand, is sliced at night through every filament and quality plus two real
-- batches, so the material factors and the plate overhead in application.yml can be checked
-- against many models instead of one Benchy. Rows hold geometry statistics and slice figures
-- only; the order number is the only link to an order and no file or person is referenced.

CREATE TABLE slice_calibrations (
    id                 UUID             PRIMARY KEY,
    model_sha256       VARCHAR(64)      NOT NULL,
    model_name         VARCHAR(255)     NOT NULL,
    model_size_bytes   BIGINT           NOT NULL,
    bbox_width_mm      DOUBLE PRECISION NOT NULL,
    bbox_depth_mm      DOUBLE PRECISION NOT NULL,
    bbox_height_mm     DOUBLE PRECISION NOT NULL,
    triangle_count     INTEGER          NOT NULL,
    -- Filament type name and quality code, as order_items store them.
    filament           VARCHAR(16)      NOT NULL,
    quality            VARCHAR(16)      NOT NULL,
    copies             INTEGER          NOT NULL,
    plates             INTEGER          NOT NULL,
    -- Figures for the whole batch of `copies` copies.
    print_seconds      INTEGER          NOT NULL,
    filament_length_mm DOUBLE PRECISION NOT NULL,
    filament_grams     DOUBLE PRECISION NOT NULL,
    warnings_count     INTEGER          NOT NULL,
    -- The manifold check of the single-copy run; null for batches and when the check failed.
    manifold           BOOLEAN,
    source             VARCHAR(16)      NOT NULL CHECK (source IN ('ORDER', 'MANUAL')),
    order_number       VARCHAR(20),
    measured_at        TIMESTAMPTZ      NOT NULL,
    -- From the CLI's --help banner; null when it printed none.
    slicer_version     VARCHAR(64),
    -- Wall-clock of the slicer run itself.
    duration_ms        BIGINT           NOT NULL
);

-- Repeats of a cell are allowed; the report takes the latest per model and cell.
CREATE INDEX slice_calibrations_model_idx ON slice_calibrations (model_sha256);
CREATE INDEX slice_calibrations_measured_at_idx ON slice_calibrations (measured_at);

-- Models waiting for their night. The file stays where it is: an order item's file in the
-- order-files storage (gone when the order is erased, the entry is then skipped with a
-- note), a model queued by hand in the calibration directory.
CREATE TABLE calibration_queue (
    id            UUID         PRIMARY KEY,
    model_sha256  VARCHAR(64)  NOT NULL,
    model_name    VARCHAR(255) NOT NULL,
    file_key      VARCHAR(255) NOT NULL,
    source        VARCHAR(16)  NOT NULL CHECK (source IN ('ORDER', 'MANUAL')),
    order_number  VARCHAR(20),
    queued_at     TIMESTAMPTZ  NOT NULL,
    started_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ,
    error         TEXT,
    cells_written INTEGER      NOT NULL DEFAULT 0
);

CREATE INDEX calibration_queue_waiting_idx ON calibration_queue (queued_at) WHERE started_at IS NULL;
CREATE INDEX calibration_queue_model_idx ON calibration_queue (model_sha256);
