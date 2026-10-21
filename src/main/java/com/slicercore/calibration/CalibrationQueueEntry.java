package com.slicercore.calibration;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * A model the owner queued for the calibration matrix, waiting, running or done. The file is
 * not copied here: an order item's file is read from the order-files storage when its turn
 * comes (and the entry is skipped with a note if the order was erased by then), a model from
 * the upload store is read from the calibration directory.
 */
@Entity
@Table(name = "calibration_queue")
public class CalibrationQueueEntry {

    /** Derived from the timestamps; serialised in lower case like the source. */
    public enum Status {
        QUEUED, RUNNING, DONE, FAILED;

        @JsonValue
        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    @Id
    private UUID id;

    @Column(name = "model_sha256", nullable = false, length = 64)
    private String modelSha256;

    @Column(name = "model_name", nullable = false)
    private String modelName;

    /** The order-files key for an order item, the file name in the calibration directory otherwise. */
    @Column(name = "file_key", nullable = false)
    private String fileKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CalibrationSource source;

    @Column(name = "order_number", length = 20)
    private String orderNumber;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** Why the model yielded nothing; null while waiting, running or after a run that wrote rows. */
    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "cells_written", nullable = false)
    private int cellsWritten;

    protected CalibrationQueueEntry() {
        // JPA
    }

    public static CalibrationQueueEntry queued(String modelSha256, String modelName, String fileKey,
                                               CalibrationSource source, String orderNumber, Instant now) {
        CalibrationQueueEntry entry = new CalibrationQueueEntry();
        entry.id = UUID.randomUUID();
        entry.modelSha256 = modelSha256;
        entry.modelName = modelName;
        entry.fileKey = fileKey;
        entry.source = source;
        entry.orderNumber = orderNumber;
        entry.queuedAt = now;
        return entry;
    }

    public void start(Instant now) {
        startedAt = now;
        finishedAt = null;
        error = null;
        cellsWritten = 0;
    }

    /** @param error null when at least one cell was measured */
    public void finish(Instant now, int cellsWritten, String error) {
        finishedAt = now;
        this.cellsWritten = cellsWritten;
        this.error = error;
    }

    public Status status() {
        if (startedAt == null) {
            return Status.QUEUED;
        }
        if (finishedAt == null) {
            return Status.RUNNING;
        }
        return error == null ? Status.DONE : Status.FAILED;
    }

    /** ".stl" or ".3mf", whichever the file key ends with. */
    public String extension() {
        String lower = fileKey.toLowerCase(Locale.ROOT);
        return lower.endsWith(".3mf") ? ".3mf" : ".stl";
    }

    public UUID getId() {
        return id;
    }

    public String getModelSha256() {
        return modelSha256;
    }

    public String getModelName() {
        return modelName;
    }

    public String getFileKey() {
        return fileKey;
    }

    public CalibrationSource getSource() {
        return source;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getError() {
        return error;
    }

    public int getCellsWritten() {
        return cellsWritten;
    }
}
