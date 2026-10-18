package com.slicercore.model;

import java.nio.file.Path;

/**
 * Raw outcome of one slicer run, parsed from {@code result.json} and the G-code trailer.
 * Internal to the backend: it carries server paths and is never returned to clients.
 */
public record SliceQuoteResult(
        String inputFileName,
        Path gcodePath,
        Path resultJsonPath,
        int returnCode,
        String errorString,
        double layerHeightMm,
        double totalPredicationSeconds,
        double mainPredicationSeconds,
        int triangleCount,
        double filamentLengthMm,
        double slicerReportedGrams,
        BoundingBox boundingBox) {
}
