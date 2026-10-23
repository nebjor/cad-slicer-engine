package com.slicercore.calibration;

import com.slicercore.catalog.PrintQuality;
import com.slicercore.slicer.SliceQuoteResult;
import com.slicercore.quote.QuotePricingService;
import com.slicercore.quote.QuoteService;
import com.slicercore.quote.SliceRecorder;
import com.slicercore.slicer.SlicerExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Every slice the quote path performs anyway becomes a calibration row (ADR 0008): the
 * dataset gathers itself from the shop's own work, one PLA single-copy row per cache miss,
 * without a single extra slicer run. Repeats are kept; the report takes the latest per cell.
 * The quote module calls this through its {@link SliceRecorder} port and swallows failures.
 */
@Component
public class QuoteSliceRecorder implements SliceRecorder {

    private static final Logger log = LoggerFactory.getLogger(QuoteSliceRecorder.class);

    private final SliceCalibrationRepository rows;
    private final QuotePricingService pricing;
    private final SlicerExecutionService slicer;
    private final Clock clock;

    public QuoteSliceRecorder(SliceCalibrationRepository rows, QuotePricingService pricing,
                              SlicerExecutionService slicer, Clock clock) {
        this.rows = rows;
        this.pricing = pricing;
        this.slicer = slicer;
        this.clock = clock;
    }

    @Override
    public void recordQuoteSlice(String modelSha256, String modelName, long modelSizeBytes, PrintQuality quality,
                                 SliceQuoteResult slice, long durationMs) {
        double grams = pricing.gramsFromLength(slice.filamentLengthMm(), QuoteService.REFERENCE_FILAMENT);
        rows.save(SliceCalibration.measured(modelSha256, modelName, modelSizeBytes, CalibrationSource.QUOTE, null,
                QuoteService.REFERENCE_FILAMENT, quality, 1, slice, grams, slicer.slicerVersion().orElse(null),
                durationMs, clock.instant()));
        log.debug("Recorded the {} slice of {} for the calibration ({} s, {} ms)", quality.code(), modelName,
                Math.round(slice.totalPredicationSeconds()), durationMs);
    }
}
