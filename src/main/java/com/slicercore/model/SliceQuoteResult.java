package com.slicercore.model;

import java.nio.file.Path;
import java.util.List;

/**
 * Raw outcome of one slicer run, parsed from {@code result.json} and the G-code trailers of
 * every generated plate. Internal to the backend: it carries server paths and is never
 * returned to clients.
 *
 * @param copies                  number of copies that were placed on the plates
 * @param plateCount              plates the slicer needed for all copies
 * @param totalPredicationSeconds print time summed over all plates
 * @param triangleCountPerCopy    triangles of one copy of the model
 * @param filamentLengthMm        extruded filament summed over all plates
 * @param boundingBox             extent of one copy of the model
 */
public record SliceQuoteResult(
        String inputFileName,
        List<Path> gcodePaths,
        Path resultJsonPath,
        int returnCode,
        String errorString,
        double layerHeightMm,
        int copies,
        int plateCount,
        double totalPredicationSeconds,
        int triangleCountPerCopy,
        double filamentLengthMm,
        double slicerReportedGrams,
        BoundingBox boundingBox) {
}
