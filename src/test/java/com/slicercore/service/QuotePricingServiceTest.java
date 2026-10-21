package com.slicercore.service;

import com.slicercore.config.QuotePricingProperties;
import com.slicercore.model.BoundingBox;
import com.slicercore.model.ColorOption;
import com.slicercore.model.FilamentType;
import com.slicercore.model.PrintQuality;
import com.slicercore.model.QuoteResponse;
import com.slicercore.model.SliceQuoteResult;
import com.slicercore.model.SlicingException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Pricing is pure arithmetic over a single-copy slicer result, so these tests pin the numbers
 * a customer sees. Reference values come from real Benchy slices on the A1 profiles; the
 * quantity scaling is checked against the batch slices measured on the production server
 * (ADR 0006).
 */
class QuotePricingServiceTest {

    private static final ColorOption BLACK = new ColorOption("black", "Black", "#1f1f1f", null);
    private static final BoundingBox BENCHY = new BoundingBox(60.000999, 31.004, 48.0);

    /** The production Benchy of 2026-10-21: 2438 s and 12.4 g of PLA, which is 4157 mm of 1.75 mm filament. */
    private static final double PRODUCTION_BENCHY_SECONDS = 2438;
    private static final double PRODUCTION_BENCHY_FILAMENT_MM = 4157.4;

    private final QuotePricingProperties properties = new QuotePricingProperties(
            "CHF", 5.00, 4.00, 3.00, 16, 1.75, 100, 300, new QuotePricingProperties.Plate(256, 256, 10, 15),
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
        SliceQuoteResult slice = slice(2239, 3880.6);
        ColorOption silk = new ColorOption("silk", "Silk", "#c94fb4", 70.0);

        QuoteResponse quote = service.price(slice, "benchy.stl", "A1", FilamentType.PLA, silk, PrintQuality.MEDIUM, 1);

        // 11.6 g at 70 CHF/kg is 0.81, against 0.40 at the filament's 35 CHF/kg.
        assertThat(quote.materialCostChf()).isEqualTo(0.8);
        assertThat(quote.color()).isEqualTo(silk);
    }

    @Test
    void aSingleCopyIsPricedAtTheSliceItself() {
        SliceQuoteResult slice = slice(2239, 3880.6);

        QuoteResponse quote = service.price(slice, "benchy.stl", "A1", FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1);

        assertThat(quote.machineCostChf()).isEqualTo(2.5);
        assertThat(quote.materialCostChf()).isEqualTo(0.4);
        assertThat(quote.filamentGrams()).isEqualTo(11.6);
        assertThat(quote.totalFilamentGrams()).isEqualTo(11.6);
        assertThat(quote.filamentLengthMm()).isEqualTo(3880.6);
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
    void fifteenBenchysFitOnTheA1Plate() {
        // Along 256 mm with 10 mm margin and 15 mm gap: 3 at 60 mm, 5 at 31 mm.
        assertThat(service.copiesPerPlate(BENCHY)).isEqualTo(15);
        // A 100 x 50 mm part: 2 x 3.
        assertThat(service.copiesPerPlate(new BoundingBox(100, 50, 10))).isEqualTo(6);
        // 236 mm usable: a part that long fits exactly once.
        assertThat(service.copiesPerPlate(new BoundingBox(236, 236, 10))).isEqualTo(1);
    }

    @Test
    void theBetterOfBothOrientationsCounts() {
        // On a 300 x 200 mm bed a 150 x 60 mm part packs 1 x 2 upright and 3 x 1 turned by 90 degrees.
        QuotePricingService wideBed = new QuotePricingService(new QuotePricingProperties(
                "CHF", 5, 4, 3, 16, 1.75, 100, 300, new QuotePricingProperties.Plate(300, 200, 10, 15), Map.of()));

        assertThat(wideBed.copiesPerPlate(new BoundingBox(150, 60, 10))).isEqualTo(3);
        assertThat(wideBed.copiesPerPlate(new BoundingBox(60, 150, 10))).isEqualTo(3);
    }

    @Test
    void tenBenchysReproduceTheMeasuredBatchOnOnePlate() {
        // Measured 2026-10-21 for ten copies arranged by the slicer: 1 plate, 21213 s, 123 g.
        SliceQuoteResult slice = slice(PRODUCTION_BENCHY_SECONDS, PRODUCTION_BENCHY_FILAMENT_MM);

        QuoteResponse quote = service.price(slice, "benchy.stl", "A1", FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 10);

        // 10 x (2438 - 300) + 1 x 300 = 21680 s, 2.2 % above the slicer's own batch.
        assertThat(quote.plateCount()).isEqualTo(1);
        assertThat(quote.totalPrintTimeSeconds()).isEqualTo(21680);
        assertThat(quote.totalPrintTimeSeconds()).isCloseTo(21213, within(21213 * 0.05));
        assertThat(quote.printTimeSeconds()).isEqualTo(2168);
        assertThat(quote.filamentGrams()).isEqualTo(12.4);
        assertThat(quote.totalFilamentGrams()).isEqualTo(124.0);
        assertThat(quote.filamentLengthMm()).isEqualTo(41574.0);
        // 6.022 h at CHF 4 is 24.09; 124 g at CHF 35/kg is 4.34; per unit 2.84, below the floor.
        assertThat(quote.machineCostChf()).isEqualTo(24.1);
        assertThat(quote.materialCostChf()).isEqualTo(4.35);
        assertThat(quote.unitPriceChf()).isEqualTo(3.0);
        assertThat(quote.totalPriceChf()).isEqualTo(35.0);
        assertThat(quote.leadTimeDays()).isEqualTo(3);
    }

    @Test
    void twentyBenchysReproduceTheMeasuredBatchOnTwoPlates() {
        // Measured 2026-10-21 for twenty copies arranged by the slicer: 2 plates, 42532 s, 246 g.
        SliceQuoteResult slice = slice(PRODUCTION_BENCHY_SECONDS, PRODUCTION_BENCHY_FILAMENT_MM);

        QuoteResponse quote = service.price(slice, "benchy.stl", "A1", FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 20);

        // 20 x 2138 + 2 x 300 = 43360 s, 1.9 % above the slicer's own batch.
        assertThat(quote.plateCount()).isEqualTo(2);
        assertThat(quote.totalPrintTimeSeconds()).isEqualTo(43360);
        assertThat(quote.totalPrintTimeSeconds()).isCloseTo(42532, within(42532 * 0.05));
        assertThat(quote.printTimeSeconds()).isEqualTo(2168);
        assertThat(quote.totalFilamentGrams()).isEqualTo(248.0);
        // 12.04 h at CHF 4 is 48.18; 248 g is 8.68; per unit 2.84, still at the floor.
        assertThat(quote.machineCostChf()).isEqualTo(48.2);
        assertThat(quote.materialCostChf()).isEqualTo(8.7);
        assertThat(quote.totalPriceChf()).isEqualTo(65.0);
        assertThat(quote.leadTimeDays()).isEqualTo(3);
    }

    @Test
    void aSixteenthBenchyOpensASecondPlate() {
        SliceQuoteResult slice = slice(PRODUCTION_BENCHY_SECONDS, PRODUCTION_BENCHY_FILAMENT_MM);

        QuoteResponse fifteen = service.price(slice, "benchy.stl", "A1", FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 15);
        QuoteResponse sixteen = service.price(slice, "benchy.stl", "A1", FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 16);

        assertThat(fifteen.plateCount()).isEqualTo(1);
        assertThat(sixteen.plateCount()).isEqualTo(2);
        // One more copy plus the overhead of the new plate.
        assertThat(sixteen.totalPrintTimeSeconds() - fifteen.totalPrintTimeSeconds()).isEqualTo(2138 + 300);
    }

    @Test
    void aTinyModelNeverCountsForLessThanAMinutePerCopy() {
        // The feasibility note's 10 mm cube: 199 s, less than the 300 s plate overhead.
        assertThat(service.perCopySeconds(199)).isEqualTo(60);
        // A 1000 s model: 700 s without the overhead is above the quarter floor of 250 s.
        assertThat(service.perCopySeconds(1000)).isEqualTo(700);
        // At 320 s the quarter (80 s) beats both the 20 s remainder and the 60 s minimum.
        assertThat(service.perCopySeconds(320)).isEqualTo(80);
        // A sub-minute slice is never inflated: one copy is the slice.
        assertThat(service.perCopySeconds(40)).isEqualTo(40);
        assertThat(service.perCopySeconds(PRODUCTION_BENCHY_SECONDS)).isEqualTo(2138);

        SliceQuoteResult cube = new SliceQuoteResult("cube.stl", List.of(Path.of("plate_1.gcode")), Path.of("result.json"),
                0, "Success.", 0.2, 1, 199, 12, 59.47, 0, new BoundingBox(10, 10, 10), List.of(), null);

        QuoteResponse one = service.price(cube, "cube.stl", "A1", FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1);
        QuoteResponse hundred = service.price(cube, "cube.stl", "A1", FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 100);

        assertThat(one.totalPrintTimeSeconds()).isEqualTo(199);
        // 100 x 60 s plus the 139 s the slice held beyond the floor, once per plate; 10 mm cubes pack 100 per plate.
        assertThat(hundred.plateCount()).isEqualTo(1);
        assertThat(hundred.totalPrintTimeSeconds()).isEqualTo(6139);
    }

    @Test
    void aModelLargerThanThePlateIsRejected() {
        SliceQuoteResult plank = new SliceQuoteResult("plank.stl", List.of(Path.of("plate_1.gcode")), Path.of("result.json"),
                0, "Success.", 0.2, 1, 5000, 12, 10000, 0, new BoundingBox(240, 20, 5), List.of(), null);

        assertThatThrownBy(() -> service.price(plank, "plank.stl", "A1", FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1))
                .isInstanceOf(SlicingException.class)
                .hasMessage("Model is larger than the build plate (256 x 256 mm with a 10 mm margin): 240.0 x 20.0 mm.");
    }

    @Test
    void unitPriceAboveFloorIsMachinePlusMaterialPerUnit() {
        // A high-quality PETG Benchy: 7331.5 s and 8924.75 mm per copy.
        SliceQuoteResult slice = slice(7331.5, 8924.75);

        QuoteResponse quote = service.price(slice, "benchy.3mf", "A1", FilamentType.PETG, BLACK, PrintQuality.HIGH, 2);

        // 2 x 7031.5 + 300 = 14363 s is 3.99 h at CHF 4; 54.5 g of PETG at CHF 42/kg.
        assertThat(quote.totalPrintTimeSeconds()).isEqualTo(14363);
        assertThat(quote.machineCostChf()).isEqualTo(15.95);
        assertThat(quote.materialCostChf()).isEqualTo(2.3);
        // The unit price is 9.1245 before rounding, so the total is 5 + 18.249 = 23.25, not 5 + 2 x 9.10.
        assertThat(quote.unitPriceChf()).isEqualTo(9.1);
        assertThat(quote.totalPriceChf()).isEqualTo(23.25);
    }

    @Test
    void roundsToFiveRappen() {
        // 3800 s at CHF 4/h is 4.2222, which rounds to 4.20, not 4.22.
        SliceQuoteResult slice = slice(3800, 0);

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
    void leadTimeFollowsTheBatchTotal() {
        // 50 Benchys: 4 plates, 50 x 2138 + 4 x 300 = 108100 s = 30.03 h, one printer day beyond the first.
        SliceQuoteResult slice = slice(PRODUCTION_BENCHY_SECONDS, PRODUCTION_BENCHY_FILAMENT_MM);

        QuoteResponse quote = service.price(slice, "benchy.stl", "A1", FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 50);

        assertThat(quote.plateCount()).isEqualTo(4);
        assertThat(quote.totalPrintTimeSeconds()).isEqualTo(108100);
        assertThat(quote.leadTimeDays()).isEqualTo(4);
    }

    @Test
    void fallsBackToTierLayerHeightWhenSlicerReportsNone() {
        SliceQuoteResult slice = new SliceQuoteResult("x.stl", List.of(Path.of("plate_1.gcode")), Path.of("result.json"),
                0, "Success.", 0, 1, 100, 10, 100, 0, BoundingBox.EMPTY, List.of(), null);

        QuoteResponse quote = service.price(slice, "x.stl", "A1", FilamentType.PLA, BLACK, PrintQuality.HIGH, 1);

        assertThat(quote.layerHeightMm()).isEqualTo(0.12);
    }

    @Test
    void passesTheSlicerWarningsAndTheManifoldCheckThroughUnchanged() {
        SliceQuoteResult flagged = slice(2239, 3880.6)
                .withInspection(List.of("Object too small", "Mesh errors reported by the slicer: open_edges = 4"), false);

        QuoteResponse quote = service.price(flagged, "benchy.stl", "A1", FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1);
        QuoteResponse clean = service.price(slice(2239, 3880.6), "benchy.stl", "A1", FilamentType.PLA, BLACK,
                PrintQuality.MEDIUM, 1);

        assertThat(quote.warnings()).containsExactly("Object too small", "Mesh errors reported by the slicer: open_edges = 4");
        assertThat(quote.manifold()).isFalse();
        assertThat(quote.totalPriceChf()).isEqualTo(clean.totalPriceChf());
        assertThat(clean.warnings()).isEmpty();
        assertThat(clean.manifold()).isNull();
    }

    /** A single-copy Benchy slice on one plate. */
    private static SliceQuoteResult slice(double totalSeconds, double filamentLengthMm) {
        return new SliceQuoteResult("benchy.stl", List.of(Path.of("plate_1.gcode")), Path.of("result.json"),
                0, "Success.", 0.2, 1, totalSeconds, 225154, filamentLengthMm, 0, BENCHY, List.of(), null);
    }
}
