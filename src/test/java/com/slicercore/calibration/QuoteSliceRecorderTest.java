package com.slicercore.calibration;

import com.slicercore.config.QuotePricingProperties;
import com.slicercore.catalog.BoundingBox;
import com.slicercore.catalog.PrintQuality;
import com.slicercore.slicer.SliceQuoteResult;
import com.slicercore.quote.QuotePricingService;
import com.slicercore.slicer.SlicerExecutionService;
import com.slicercore.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A quote's slice becomes one PLA single-copy row with source "quote" and no order number. */
class QuoteSliceRecorderTest {

    private final SliceCalibrationRepository rows = mock(SliceCalibrationRepository.class);
    private final SlicerExecutionService slicer = mock(SlicerExecutionService.class);
    private final MutableClock clock = MutableClock.at("2026-10-21T16:00:00Z");
    private final QuoteSliceRecorder recorder = new QuoteSliceRecorder(rows,
            new QuotePricingService(new QuotePricingProperties("CHF", 4, 3, 16, 1.75, 100, 300,
                    new QuotePricingProperties.Plate(256, 256, 10, 15), Map.of(), QuotePricingProperties.Material.UNSCALED)),
            slicer, clock);

    @Test
    void recordsTheSliceAsAQuoteRowOfTheReferenceFilament() {
        when(slicer.slicerVersion()).thenReturn(Optional.of("BambuStudio-02.01.01.52"));
        SliceQuoteResult slice = new SliceQuoteResult(List.of(), 0, "Success.",
                0.2, 1, 2442.0, 225154, 4169.0, 0, new BoundingBox(60, 31, 48), List.of("thin wall"), false);

        recorder.recordQuoteSlice("a".repeat(64), "3DBenchy.stl", 11_000_000, PrintQuality.MEDIUM, slice, 8500);

        ArgumentCaptor<SliceCalibration> saved = ArgumentCaptor.forClass(SliceCalibration.class);
        verify(rows).save(saved.capture());
        SliceCalibration row = saved.getValue();
        assertThat(row.getSource()).isEqualTo(CalibrationSource.QUOTE);
        assertThat(row.getOrderNumber()).isNull();
        assertThat(row.getModelSha256()).isEqualTo("a".repeat(64));
        assertThat(row.getModelName()).isEqualTo("3DBenchy.stl");
        assertThat(row.getModelSizeBytes()).isEqualTo(11_000_000);
        assertThat(row.getFilament()).isEqualTo("PLA");
        assertThat(row.getQuality()).isEqualTo("medium");
        assertThat(row.getCopies()).isEqualTo(1);
        assertThat(row.getPlates()).isEqualTo(1);
        assertThat(row.getPrintSeconds()).isEqualTo(2442);
        assertThat(row.getFilamentLengthMm()).isEqualTo(4169.0);
        // pi * 0.875^2 * 4169 mm at 1.24 g/cm^3
        assertThat(row.getFilamentGrams()).isCloseTo(12.43, within(0.01));
        assertThat(row.getTriangleCount()).isEqualTo(225154);
        assertThat(row.getBboxWidthMm()).isEqualTo(60);
        assertThat(row.getBboxDepthMm()).isEqualTo(31);
        assertThat(row.getBboxHeightMm()).isEqualTo(48);
        assertThat(row.getWarningsCount()).isEqualTo(1);
        assertThat(row.getManifold()).isFalse();
        assertThat(row.getSlicerVersion()).isEqualTo("BambuStudio-02.01.01.52");
        assertThat(row.getDurationMs()).isEqualTo(8500);
        assertThat(row.getMeasuredAt()).isEqualTo(clock.instant());
    }

    @Test
    void anUnknownSlicerVersionIsStoredAsNull() {
        when(slicer.slicerVersion()).thenReturn(Optional.empty());
        SliceQuoteResult slice = new SliceQuoteResult(List.of(), 0, "Success.",
                0.2, 1, 100.0, 10, 50.0, 0, new BoundingBox(10, 10, 10), List.of(), null);

        recorder.recordQuoteSlice("b".repeat(64), "cube.stl", 84, PrintQuality.HIGH, slice, 10);

        ArgumentCaptor<SliceCalibration> saved = ArgumentCaptor.forClass(SliceCalibration.class);
        verify(rows).save(saved.capture());
        assertThat(saved.getValue().getSlicerVersion()).isNull();
        assertThat(saved.getValue().getManifold()).isNull();
        assertThat(saved.getValue().getQuality()).isEqualTo("high");
    }
}
