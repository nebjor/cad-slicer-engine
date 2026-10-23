package com.slicercore.slicer;

import java.nio.file.Path;
import java.util.List;

/**
 * Raw outcome of one slicer run of a single copy of the model with the reference filament,
 * parsed from {@code result.json} and the G-code trailers of every generated plate, plus what
 * the CLI reported about the model itself. Internal to the backend: it carries server paths
 * and is never returned to clients. Quantities (ADR 0006) and the other filaments (ADR 0007)
 * are derived from it arithmetically.
 *
 * @param plateCount              plates the slicer wrote; one for a single copy
 * @param totalPredicationSeconds print time of the single copy, summed over the plates
 * @param triangleCountPerCopy    triangles of one copy of the model
 * @param filamentLengthMm        extruded filament for the single copy
 * @param boundingBox             extent of one copy of the model
 * @param warnings                what the slicer warned about, as it printed it; never null,
 *                                deduplicated and bounded in count and length
 * @param manifold                whether {@code --info} found the mesh watertight; null when
 *                                the check did not run or failed
 */
public record SliceQuoteResult(
        List<Path> gcodePaths,
        int returnCode,
        String errorString,
        double layerHeightMm,
        int plateCount,
        double totalPredicationSeconds,
        int triangleCountPerCopy,
        double filamentLengthMm,
        double slicerReportedGrams,
        BoundingBox boundingBox,
        List<String> warnings,
        Boolean manifold) {

    public SliceQuoteResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** The same slice with the model inspection added; the inspection runs after the parse. */
    public SliceQuoteResult withInspection(List<String> warnings, Boolean manifold) {
        return new SliceQuoteResult(gcodePaths, returnCode, errorString,
                layerHeightMm, plateCount, totalPredicationSeconds, triangleCountPerCopy,
                filamentLengthMm, slicerReportedGrams, boundingBox, warnings, manifold);
    }
}
