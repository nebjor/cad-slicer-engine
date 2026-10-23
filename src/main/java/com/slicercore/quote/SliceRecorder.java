package com.slicercore.quote;

import com.slicercore.slicer.PrintQuality;
import com.slicercore.slicer.SliceQuoteResult;

/**
 * Something that keeps a record of every slice the quote path performs, such as the slice
 * calibration dataset (ADR 0008). A port of the quote module: implementations are picked up
 * as beans, the quote module never learns what they do with the data. {@link QuoteService}
 * calls it after a cache miss only, outside the slicer slot, and treats any exception as a
 * warning: nothing here may cost a customer the quote.
 */
public interface SliceRecorder {

    /**
     * @param modelSha256    content hash of the model, the id the upload store uses
     * @param modelName      the upload's display name, without any path
     * @param modelSizeBytes size of the model file
     * @param quality        the quality that was sliced; the filament is always the reference (PLA)
     * @param slice          the single-copy result as the quote cached it
     * @param durationMs     wall-clock of the slicer run, the slot wait excluded
     */
    void recordQuoteSlice(String modelSha256, String modelName, long modelSizeBytes, PrintQuality quality,
                          SliceQuoteResult slice, long durationMs);
}
