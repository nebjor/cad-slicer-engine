package com.slicercore.calibration;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** One queue entry as the admin page shows it. */
public record CalibrationQueueResponse(
        UUID id,
        String modelSha256,
        String modelName,
        CalibrationSource source,
        @Schema(nullable = true) String orderNumber,
        CalibrationQueueEntry.Status status,
        Instant queuedAt,
        @Schema(nullable = true) Instant startedAt,
        @Schema(nullable = true) Instant finishedAt,
        @Schema(nullable = true, description = "Why the model yielded no row; null unless the status is failed") String error,
        int cellsWritten) {

    public static CalibrationQueueResponse from(CalibrationQueueEntry entry) {
        return new CalibrationQueueResponse(entry.getId(), entry.getModelSha256(), entry.getModelName(),
                entry.getSource(), entry.getOrderNumber(), entry.status(), entry.getQueuedAt(), entry.getStartedAt(),
                entry.getFinishedAt(), entry.getError(), entry.getCellsWritten());
    }
}
