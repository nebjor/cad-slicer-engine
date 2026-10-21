package com.slicercore.service;

import com.slicercore.config.QuotePricingProperties;
import com.slicercore.model.BoundingBox;
import com.slicercore.model.ColorOption;
import com.slicercore.model.FilamentType;
import com.slicercore.model.PrintQuality;
import com.slicercore.model.QuoteResponse;
import com.slicercore.model.SliceQuoteResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pricing is pure arithmetic over the slicer result, so these tests pin the numbers a
 * customer sees. Reference values come from real Benchy slices on the A1 profiles.
 */
class QuotePricingServiceTest {

    private static final ColorOption BLACK = new ColorOption("black", "Black", "#1f1f1f", null);

    private final QuotePricingProperties properties = new QuotePricingProperties(
            "CHF", 5.00, 4.00, 3.00, 16, 1.75, 20,
            Map.of(FilamentType.PLA, 35.0, FilamentType.PETG, 42.0));

    private final QuotePricingService service = new QuotePricingService(properties);

    @Test
    void derivesMassFromFilamentLengthAndDensity() {
        // 3567 mm of 1.75 mm filament is 8.58 cm3; PETG at 1.27 g/cm3 weighs 10.9 g.
        assertThat(service.gramsFromLength(3567, FilamentType.PETG)).isCloseTo(10.9, within(0.05));
        assertThat(service.gramsFromLength(3567, FilamentType.PLA)).isCloseTo(10.64, within(0.05));
        assertThat(service.gramsFromLength(0, FilamentType.PLA)).isZero();
    }

    @Test
    void aColourWithItsOwnPriceIsChargedAtThatPrice() {
        SliceQuoteResult slice = slice(1, 1, 2239, 3880.6);
        ColorOption silk = new ColorOption("silk", "Silk", "#c94fb4", 70.0);

        QuoteResponse quote = service.price(slice, "benchy.stl", "A1", FilamentType.PLA, silk, PrintQuality.MEDIUM, 1);

        // 11.6 g at 70 CHF/kg is 0.81, against 0.40 at the filament's 35 CHF/kg.
        assertThat(quote.materialCostChf()).isEqualTo(0.8);
        assertThat(quote.color()).isEqualTo(silk);
    }

    @Test
    void pricesSingleBenchyWithMinimumUnitPrice() {
        SliceQuoteResult slice = slice(1, 1, 2239, 3880.6);

        QuoteResponse quote = service.price(slice, "benchy.stl", "A1", FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1);

        assertThat(quote.machineCostChf()).isEqualTo(2.5);
        assertThat(quote.materialCostChf()).isEqualTo(0.4);
        assertThat(quote.filamentGrams()).isEqualTo(11.6);
        // Machine plus material is 2.89, below the 3.00 floor.
        assertThat(quote.unitPriceChf()).isEqualTo(3.0);
        assertThat(quote.totalPriceChf()).isEqualTo(8.0);
        assertThat(quote.setupFeeChf()).isEqualTo(5.0);
        assertThat(quote.leadTimeDays()).isEqualTo(3);
        assertThat(quote.currency()).isEqualTo("CHF");
        assertThat(quote.color()).isEqualTo(BLACK);
        assertThat(quote.printTimeSeconds()).isEqualTo(2239);
        assertThat(quote.totalPrintTimeSeconds()).isEqualTo(2239);
        assertThat(quote.plateCount()).isEqualTo(1);
        assertThat(quote.layerHeightMm()).isEqualTo(0.2);
        assertThat(quote.boundingBoxMm()).isEqualTo(new BoundingBox(60.0, 31.0, 48.0));
    }

    @Test
    void chargesSetupOnceAndDividesBatchTotalsPerUnit() {
        SliceQuoteResult slice = slice(10, 1, 18375, 38529.8);

        QuoteResponse quote = service.price(slice, "benchy.stl", "A1", FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 10);

        assertThat(quote.machineCostChf()).isEqualTo(20.4);
        assertThat(quote.materialCostChf()).isEqualTo(4.0);
        assertThat(quote.totalFilamentGrams()).isEqualTo(114.9);
        assertThat(quote.filamentGrams()).isEqualTo(11.5);
        assertThat(quote.printTimeSeconds()).isEqualTo(1838);
        assertThat(quote.totalPrintTimeSeconds()).isEqualTo(18375);
        assertThat(quote.unitPriceChf()).isEqualTo(3.0);
        assertThat(quote.totalPriceChf()).isEqualTo(35.0);
    }

    @Test
    void unitPriceAboveFloorIsMachinePlusMaterialPerUnit() {
        SliceQuoteResult slice = slice(2, 1, 14663, 17849.5);

        QuoteResponse quote = service.price(slice, "benchy.3mf", "A1", FilamentType.PETG, BLACK, PrintQuality.HIGH, 2);

        assertThat(quote.machineCostChf()).isEqualTo(16.3);
        assertThat(quote.materialCostChf()).isEqualTo(2.3);
        assertThat(quote.unitPriceChf()).isEqualTo(9.3);
        assertThat(quote.totalPriceChf()).isEqualTo(23.6);
    }

    @Test
    void roundsToFiveRappen() {
        // 3800 s at CHF 4/h is 4.2222, which rounds to 4.20, not 4.22.
        SliceQuoteResult slice = slice(1, 1, 3800, 0);

        QuoteResponse quote = service.price(slice, "x.stl", "A1", FilamentType.PLA, BLACK, PrintQuality.COARSE, 1);

        assertThat(quote.machineCostChf()).isEqualTo(4.2);
        assertThat(quote.materialCostChf()).isZero();
    }

    @Test
    void leadTimeGrowsByOneDayPerFullPrinterDay() {
        assertThat(service.leadTimeDays(0.5, PrintQuality.COARSE)).isEqualTo(2);
        assertThat(service.leadTimeDays(16, PrintQuality.MEDIUM)).isEqualTo(3);
        assertThat(service.leadTimeDays(16.1, PrintQuality.MEDIUM)).isEqualTo(4);
        assertThat(service.leadTimeDays(40, PrintQuality.HIGH)).isEqualTo(6);
    }

    @Test
    void fallsBackToTierLayerHeightWhenSlicerReportsNone() {
        SliceQuoteResult slice = new SliceQuoteResult("x.stl", List.of(Path.of("plate_1.gcode")), Path.of("result.json"),
                0, "Success.", 0, 1, 1, 100, 10, 100, 0, BoundingBox.EMPTY, List.of(), null);

        QuoteResponse quote = service.price(slice, "x.stl", "A1", FilamentType.PLA, BLACK, PrintQuality.HIGH, 1);

        assertThat(quote.layerHeightMm()).isEqualTo(0.12);
    }

    @Test
    void passesTheSlicerWarningsAndTheManifoldCheckThroughUnchanged() {
        SliceQuoteResult flagged = slice(1, 1, 2239, 3880.6)
                .withInspection(List.of("Object too small", "Mesh errors reported by the slicer: open_edges = 4"), false);

        QuoteResponse quote = service.price(flagged, "benchy.stl", "A1", FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1);
        QuoteResponse clean = service.price(slice(1, 1, 2239, 3880.6), "benchy.stl", "A1", FilamentType.PLA, BLACK,
                PrintQuality.MEDIUM, 1);

        assertThat(quote.warnings()).containsExactly("Object too small", "Mesh errors reported by the slicer: open_edges = 4");
        assertThat(quote.manifold()).isFalse();
        assertThat(quote.totalPriceChf()).isEqualTo(clean.totalPriceChf());
        assertThat(clean.warnings()).isEmpty();
        assertThat(clean.manifold()).isNull();
    }

    private static SliceQuoteResult slice(int copies, int plates, double totalSeconds, double filamentLengthMm) {
        return new SliceQuoteResult("benchy.stl", List.of(Path.of("plate_1.gcode")), Path.of("result.json"),
                0, "Success.", 0.2, copies, plates, totalSeconds, 225154, filamentLengthMm, 0,
                new BoundingBox(60.000999, 31.004, 48.0), List.of(), null);
    }
}
