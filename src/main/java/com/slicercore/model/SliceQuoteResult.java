package com.slicercore.model;

import java.nio.file.Path;
import java.util.List;

/**
 * Raw outcome of one slicer run, parsed from {@code result.json} and the G-code trailers of
 * every generated plate, plus what the CLI reported about the model itself. Internal to the
 * backend: it carries server paths and is never returned to clients.
 *
 * @param copies                  number of copies that were placed on the plates
 * @param plateCount              plates the slicer needed for all copies
 * @param totalPredicationSeconds print time summed over all plates
 * @param triangleCountPerCopy    triangles of one copy of the model
 * @param filamentLengthMm        extruded filament summed over all plates
 * @param boundingBox             extent of one copy of the model
 * @param warnings                what the slicer warned about, as it printed it; never null,
 *                                deduplicated and bounded in count and length
 * @param manifold                whether {@code --info} found the mesh watertight; null when
 *                                the check did not run or failed
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
        BoundingBox boundingBox,
        List<String> warnings,
        Boolean manifold) {

    public SliceQuoteResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** The same slice with the model inspection added; the inspection runs after the parse. */
    public SliceQuoteResult withInspection(List<String> warnings, Boolean manifold) {
        return new SliceQuoteResult(inputFileName, gcodePaths, resultJsonPath, returnCode, errorString,
                layerHeightMm, copies, plateCount, totalPredicationSeconds, triangleCountPerCopy,
                filamentLengthMm, slicerReportedGrams, boundingBox, warnings, manifold);
    }
}
