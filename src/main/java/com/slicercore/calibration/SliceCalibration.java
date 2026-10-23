package com.slicercore.calibration;

import com.slicercore.slicer.BoundingBox;
import com.slicercore.slicer.FilamentType;
import com.slicercore.slicer.PrintQuality;
import com.slicercore.slicer.SliceQuoteResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One measured cell of the calibration matrix: what the slicer predicted for {@code copies}
 * copies of a model with a filament profile and a quality (ADR 0008): every slice the quote
 * path performs (source {@code quote}) and every cell of a matrix the owner asked for. Holds
 * geometry statistics and slice figures only, never a file or a person; the order number is
 * the only link to an order. Rows are append-only and never updated, repeats are allowed.
 */
@Entity
@Table(name = "slice_calibrations")
public class SliceCalibration {

    @Id
    private UUID id;

    @Column(name = "model_sha256", nullable = false, length = 64)
    private String modelSha256;

    @Column(name = "model_name", nullable = false)
    private String modelName;

    @Column(name = "model_size_bytes", nullable = false)
    private long modelSizeBytes;

    @Column(name = "bbox_width_mm", nullable = false)
    private double bboxWidthMm;

    @Column(name = "bbox_depth_mm", nullable = false)
    private double bboxDepthMm;

    @Column(name = "bbox_height_mm", nullable = false)
    private double bboxHeightMm;

    @Column(name = "triangle_count", nullable = false)
    private int triangleCount;

    /** {@link FilamentType} name, as the order items store it. */
    @Column(nullable = false, length = 16)
    private String filament;

    /** {@link PrintQuality} code ("medium"), as the order items store it. */
    @Column(nullable = false, length = 16)
    private String quality;

    @Column(nullable = false)
    private int copies;

    @Column(nullable = false)
    private int plates;

    /** Predicted print time of the whole batch. */
    @Column(name = "print_seconds", nullable = false)
    private int printSeconds;

    /** Extruded length of the whole batch, from the G-code trailers. */
    @Column(name = "filament_length_mm", nullable = false)
    private double filamentLengthMm;

    /** Mass of that length at the filament's density, as the quote derives it. */
    @Column(name = "filament_grams", nullable = false)
    private double filamentGrams;

    @Column(name = "warnings_count", nullable = false)
    private int warningsCount;

    /** The manifold check of the single-copy run; null for batches and when the check failed. */
    @Column(name = "manifold")
    private Boolean manifold;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CalibrationSource source;

    @Column(name = "order_number", length = 20)
    private String orderNumber;

    @Column(name = "measured_at", nullable = false)
    private Instant measuredAt;

    @Column(name = "slicer_version", length = 64)
    private String slicerVersion;

    /** Wall-clock of the slicer run, the slot wait excluded. */
    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    protected SliceCalibration() {
        // JPA
    }

    public static SliceCalibration measured(String modelSha256, String modelName, long modelSizeBytes,
                                            CalibrationSource source, String orderNumber,
                                            FilamentType filament, PrintQuality quality, int copies,
                                            SliceQuoteResult slice, double filamentGrams,
                                            String slicerVersion, long durationMs, Instant measuredAt) {
        SliceCalibration row = new SliceCalibration();
        row.id = UUID.randomUUID();
        row.modelSha256 = modelSha256;
        row.modelName = modelName;
        row.modelSizeBytes = modelSizeBytes;
        row.bboxWidthMm = slice.boundingBox().widthMm();
        row.bboxDepthMm = slice.boundingBox().depthMm();
        row.bboxHeightMm = slice.boundingBox().heightMm();
        row.triangleCount = slice.triangleCountPerCopy();
        row.filament = filament.name();
        row.quality = quality.code();
        row.copies = copies;
        row.plates = slice.plateCount();
        row.printSeconds = (int) Math.round(slice.totalPredicationSeconds());
        row.filamentLengthMm = slice.filamentLengthMm();
        row.filamentGrams = filamentGrams;
        row.warningsCount = slice.warnings().size();
        row.manifold = slice.manifold();
        row.source = source;
        row.orderNumber = orderNumber;
        row.measuredAt = measuredAt;
        row.slicerVersion = slicerVersion;
        row.durationMs = durationMs;
        return row;
    }

    /** A row written by hand, for tests and the report arithmetic. */
    public static SliceCalibration of(String modelSha256, String modelName, CalibrationSource source, String orderNumber,
                                      FilamentType filament, PrintQuality quality, int copies, int plates,
                                      int printSeconds, double filamentLengthMm, BoundingBox bbox, Instant measuredAt) {
        SliceCalibration row = new SliceCalibration();
        row.id = UUID.randomUUID();
        row.modelSha256 = modelSha256;
        row.modelName = modelName;
        row.modelSizeBytes = 0;
        row.bboxWidthMm = bbox.widthMm();
        row.bboxDepthMm = bbox.depthMm();
        row.bboxHeightMm = bbox.heightMm();
        row.triangleCount = 0;
        row.filament = filament.name();
        row.quality = quality.code();
        row.copies = copies;
        row.plates = plates;
        row.printSeconds = printSeconds;
        row.filamentLengthMm = filamentLengthMm;
        row.filamentGrams = 0;
        row.warningsCount = 0;
        row.source = source;
        row.orderNumber = orderNumber;
        row.measuredAt = measuredAt;
        row.durationMs = 0;
        return row;
    }

    public BoundingBox boundingBox() {
        return new BoundingBox(bboxWidthMm, bboxDepthMm, bboxHeightMm);
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

    public long getModelSizeBytes() {
        return modelSizeBytes;
    }

    public double getBboxWidthMm() {
        return bboxWidthMm;
    }

    public double getBboxDepthMm() {
        return bboxDepthMm;
    }

    public double getBboxHeightMm() {
        return bboxHeightMm;
    }

    public int getTriangleCount() {
        return triangleCount;
    }

    public String getFilament() {
        return filament;
    }

    public String getQuality() {
        return quality;
    }

    public int getCopies() {
        return copies;
    }

    public int getPlates() {
        return plates;
    }

    public int getPrintSeconds() {
        return printSeconds;
    }

    public double getFilamentLengthMm() {
        return filamentLengthMm;
    }

    public double getFilamentGrams() {
        return filamentGrams;
    }

    public int getWarningsCount() {
        return warningsCount;
    }

    public Boolean getManifold() {
        return manifold;
    }

    public CalibrationSource getSource() {
        return source;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public Instant getMeasuredAt() {
        return measuredAt;
    }

    public String getSlicerVersion() {
        return slicerVersion;
    }

    public long getDurationMs() {
        return durationMs;
    }
}
