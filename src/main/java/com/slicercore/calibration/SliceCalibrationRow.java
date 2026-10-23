package com.slicercore.calibration;

import com.slicercore.slicer.BoundingBox;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** One raw {@code slice_calibrations} row as the admin page lists it; nothing personal in it. */
public record SliceCalibrationRow(
        UUID id,
        Instant measuredAt,
        CalibrationSource source,
        String modelName,
        String modelSha256,
        long modelSizeBytes,
        int triangleCount,
        BoundingBox bbox,
        String filament,
        String quality,
        int copies,
        int plates,
        int printSeconds,
        double filamentLengthMm,
        double filamentGrams,
        int warningsCount,
        @Schema(nullable = true, description = "The manifold check of a single copy; null for batches and when the check failed") Boolean manifold,
        long durationMs,
        @Schema(nullable = true) String slicerVersion,
        @Schema(nullable = true) String orderNumber) {

    public static SliceCalibrationRow from(SliceCalibration row) {
        return new SliceCalibrationRow(row.getId(), row.getMeasuredAt(), row.getSource(), row.getModelName(),
                row.getModelSha256(), row.getModelSizeBytes(), row.getTriangleCount(), row.boundingBox(),
                row.getFilament(), row.getQuality(), row.getCopies(), row.getPlates(), row.getPrintSeconds(),
                row.getFilamentLengthMm(), row.getFilamentGrams(), row.getWarningsCount(), row.getManifold(),
                row.getDurationMs(), row.getSlicerVersion(), row.getOrderNumber());
    }
}
