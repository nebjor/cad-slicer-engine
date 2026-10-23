package com.slicercore.calibration;

import com.slicercore.calibration.CalibrationReport.CalibrationStats;
import com.slicercore.config.QuotePricingProperties;
import com.slicercore.slicer.BoundingBox;
import com.slicercore.slicer.FilamentType;
import com.slicercore.slicer.PrintQuality;
import com.slicercore.quote.QuotePricingService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.slicercore.slicer.FilamentType.PCTG;
import static com.slicercore.slicer.FilamentType.PETG;
import static com.slicercore.slicer.FilamentType.PLA;
import static com.slicercore.slicer.PrintQuality.COARSE;
import static com.slicercore.slicer.PrintQuality.HIGH;
import static com.slicercore.slicer.PrintQuality.MEDIUM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The report over a hand-made dataset: the production Benchy of ADR 0006 and 0007 plus a
 * second, flatter model, so every statistic has two values and the arithmetic can be checked
 * by hand. Factors are rounded to three decimals by the report.
 */
class CalibrationReportServiceTest {

    private static final BoundingBox BENCHY = new BoundingBox(60, 31, 48);
    private static final BoundingBox PLATE = new BoundingBox(100, 100, 20);
    private static final Instant DAY_ONE = Instant.parse("2026-10-22T03:10:00Z");
    private static final Instant DAY_TWO = Instant.parse("2026-10-23T03:10:00Z");
    private static final String BENCHY_SHA = "a".repeat(64);
    private static final String PLATE_SHA = "b".repeat(64);

    private final QuotePricingProperties properties = new QuotePricingProperties("CHF", 4, 3, 16, 1.75, 100, 300,
            new QuotePricingProperties.Plate(256, 256, 10, 15), Map.of(),
            new QuotePricingProperties.Material(Map.of(PLA, 1.0, PETG, 1.38, PCTG, 1.48), Map.of(PLA, 1.0, PETG, 0.97, PCTG, 0.97)));
    private final SliceCalibrationRepository rows = mock(SliceCalibrationRepository.class);
    private final CalibrationReportService service =
            new CalibrationReportService(rows, new QuotePricingService(properties), properties);

    /** The Benchy's eleven cells as measured on the production server on 2026-10-21. */
    private static List<SliceCalibration> benchy() {
        List<SliceCalibration> cells = new ArrayList<>();
        cells.add(single(BENCHY_SHA, PLA, COARSE, 2170, 4339));
        cells.add(single(BENCHY_SHA, PLA, MEDIUM, 2442, 4169));
        cells.add(single(BENCHY_SHA, PLA, HIGH, 3686, 4065));
        cells.add(single(BENCHY_SHA, PETG, COARSE, 2934, 4207));
        cells.add(single(BENCHY_SHA, PETG, MEDIUM, 3341, 4042));
        cells.add(single(BENCHY_SHA, PETG, HIGH, 5263, 3941));
        cells.add(single(BENCHY_SHA, PCTG, COARSE, 3218, 4207));
        cells.add(single(BENCHY_SHA, PCTG, MEDIUM, 3570, 4042));
        cells.add(single(BENCHY_SHA, PCTG, HIGH, 5547, 3941));
        cells.add(batch(BENCHY_SHA, 4, 1, 8605, 4 * 4169, BENCHY, DAY_ONE));
        cells.add(batch(BENCHY_SHA, 10, 1, 21213, 10 * 4169, BENCHY, DAY_ONE));
        return cells;
    }

    /** A 100 x 100 mm part: PETG at exactly 1.4 and 0.9, batches with exactly 300 s per plate. */
    private static List<SliceCalibration> plate() {
        return List.of(
                SliceCalibration.of(PLATE_SHA, "plate.stl", CalibrationSource.MANUAL, null, PLA, MEDIUM, 1, 1, 4000, 1000, PLATE, DAY_TWO),
                SliceCalibration.of(PLATE_SHA, "plate.stl", CalibrationSource.MANUAL, null, PETG, MEDIUM, 1, 1, 5600, 900, PLATE, DAY_TWO),
                batch(PLATE_SHA, 4, 1, 4 * 3700 + 300, 4000, PLATE, DAY_TWO),
                // Four copies fit a plate, so ten copies take three.
                batch(PLATE_SHA, 10, 3, 10 * 3700 + 3 * 300, 10000, PLATE, DAY_TWO));
    }

    private static SliceCalibration single(String sha, FilamentType filament, PrintQuality quality, int seconds, double length) {
        return SliceCalibration.of(sha, "benchy.stl", CalibrationSource.ORDER, "POD-1000", filament, quality, 1, 1,
                seconds, length, BENCHY, DAY_ONE);
    }

    private static SliceCalibration batch(String sha, int copies, int plates, int seconds, double length, BoundingBox bbox, Instant at) {
        return SliceCalibration.of(sha, sha.equals(BENCHY_SHA) ? "benchy.stl" : "plate.stl",
                sha.equals(BENCHY_SHA) ? CalibrationSource.ORDER : CalibrationSource.MANUAL,
                sha.equals(BENCHY_SHA) ? "POD-1000" : null, PLA, MEDIUM, copies, plates, seconds, length, bbox, at);
    }

    private static List<SliceCalibration> both() {
        List<SliceCalibration> all = new ArrayList<>(benchy());
        all.addAll(plate());
        return all;
    }

    @Test
    void materialFactorsArePerModelRatiosToThePlaSliceAtTheSameQuality() {
        CalibrationReport report = service.report(both());

        assertThat(report.models()).isEqualTo(2);
        assertThat(report.rows()).isEqualTo(15);
        assertThat(report.sources()).containsExactly(Map.entry("quote", 0L), Map.entry("order", 11L), Map.entry("manual", 4L));
        assertThat(report.timeFactor().keySet()).containsExactly("PETG", "PCTG");
        assertThat(report.timeFactor().get("PETG").keySet()).containsExactly("coarse", "medium", "high", "all");

        // PETG medium: Benchy 3341 / 2442 = 1.368, plate 1.4.
        CalibrationStats petgMedium = report.timeFactor().get("PETG").get("medium");
        assertThat(petgMedium.n()).isEqualTo(2);
        assertThat(petgMedium.mean()).isCloseTo(1.384, within(0.001));
        assertThat(petgMedium.median()).isCloseTo(1.384, within(0.001));
        assertThat(petgMedium.min()).isCloseTo(1.368, within(0.001));
        assertThat(petgMedium.max()).isCloseTo(1.4, within(0.001));
        // Only the Benchy has coarse and high cells.
        assertThat(report.timeFactor().get("PETG").get("coarse").n()).isEqualTo(1);
        assertThat(report.timeFactor().get("PETG").get("coarse").mean()).isCloseTo(1.352, within(0.001));
        assertThat(report.timeFactor().get("PETG").get("high").mean()).isCloseTo(1.428, within(0.001));
        assertThat(report.timeFactor().get("PETG").get("all").n()).isEqualTo(4);
        assertThat(report.timeFactor().get("PCTG").get("medium").n()).isEqualTo(1);
        assertThat(report.timeFactor().get("PCTG").get("medium").mean()).isCloseTo(1.462, within(0.001));
        assertThat(report.timeFactor().get("PCTG").get("all").n()).isEqualTo(3);

        // Length: Benchy 4042 / 4169 = 0.970, plate 0.9.
        CalibrationStats petgLength = report.lengthFactor().get("PETG").get("medium");
        assertThat(petgLength.n()).isEqualTo(2);
        assertThat(petgLength.mean()).isCloseTo(0.935, within(0.001));
        assertThat(petgLength.max()).isCloseTo(0.970, within(0.001));
        assertThat(report.lengthFactor().get("PCTG").get("all").mean()).isCloseTo(0.970, within(0.001));

        assertThat(report.configured().timeFactor()).containsExactly(Map.entry("PCTG", 1.48), Map.entry("PETG", 1.38));
        assertThat(report.configured().lengthFactor()).containsEntry("PETG", 0.97);
        assertThat(report.configured().plateOverheadSeconds()).isEqualTo(300);
    }

    @Test
    void quantityStatisticsCompareTheRealBatchWithTheSingleCopy() {
        CalibrationReport report = service.report(both());

        assertThat(report.quantity().keySet()).containsExactly("4", "10");
        CalibrationReport.QuantityStats four = report.quantity().get("4");
        // (8605 / 4) / 2442 = 0.881 and (15100 / 4) / 4000 = 0.944.
        assertThat(four.n()).isEqualTo(2);
        assertThat(four.perCopyRatio().min()).isCloseTo(0.881, within(0.001));
        assertThat(four.perCopyRatio().max()).isCloseTo(0.944, within(0.001));
        // (4 x 2442 - 8605) / 3 = 387.7 and exactly 300.
        assertThat(four.plateOverheadSeconds().n()).isEqualTo(2);
        assertThat(four.plateOverheadSeconds().mean()).isCloseTo(343.833, within(0.001));
        assertThat(four.plateOverheadSeconds().median()).isCloseTo(343.833, within(0.001));

        CalibrationReport.QuantityStats ten = report.quantity().get("10");
        assertThat(ten.n()).isEqualTo(2);
        assertThat(ten.perCopyRatio().min()).isCloseTo(0.869, within(0.001));
        // The plate's ten copies spilled onto three plates, so only the Benchy yields an overhead.
        assertThat(ten.plateOverheadSeconds().n()).isEqualTo(1);
        assertThat(ten.plateOverheadSeconds().mean()).isCloseTo(356.333, within(0.001));
    }

    @Test
    void arithmeticErrorIsTheConfiguredQuantityFormulaAgainstTheMeasuredBatchInPercent() {
        CalibrationReport report = service.report(both());

        // Benchy: 4 x (2442 - 300) + 300 = 8868 against 8605 = +3.06 %; plate: 15100 exactly.
        CalibrationReport.ArithmeticError four = report.arithmeticError().get("4");
        assertThat(four.n()).isEqualTo(2);
        assertThat(four.meanPercent()).isCloseTo(1.528, within(0.001));
        assertThat(four.meanAbsPercent()).isCloseTo(1.528, within(0.001));
        assertThat(four.maxAbsPercent()).isCloseTo(3.056, within(0.001));
        // Benchy: 10 x 2142 + 300 = 21720 against 21213 = +2.39 %; plate: three plates, 37900 exactly.
        CalibrationReport.ArithmeticError ten = report.arithmeticError().get("10");
        assertThat(ten.n()).isEqualTo(2);
        assertThat(ten.maxAbsPercent()).isCloseTo(2.390, within(0.001));
        assertThat(ten.meanPercent()).isCloseTo(1.195, within(0.001));
    }

    @Test
    void modelsAreListedNewestFirstWithTheirCellCountAndNoPersonalData() {
        CalibrationReport report = service.report(both());

        assertThat(report.calibratedModels()).hasSize(2);
        CalibrationReport.CalibratedModel plate = report.calibratedModels().getFirst();
        assertThat(plate.modelName()).isEqualTo("plate.stl");
        assertThat(plate.modelSha256()).isEqualTo(PLATE_SHA);
        assertThat(plate.source()).isEqualTo(CalibrationSource.MANUAL);
        assertThat(plate.orderNumber()).isNull();
        assertThat(plate.measuredAt()).isEqualTo(DAY_TWO);
        assertThat(plate.cells()).isEqualTo(4);
        assertThat(plate.bbox()).isEqualTo(PLATE);
        CalibrationReport.CalibratedModel benchy = report.calibratedModels().getLast();
        assertThat(benchy.source()).isEqualTo(CalibrationSource.ORDER);
        assertThat(benchy.orderNumber()).isEqualTo("POD-1000");
        assertThat(benchy.cells()).isEqualTo(11);
    }

    @Test
    void aRepeatedCellCountsOnceWithItsLatestMeasurement() {
        List<SliceCalibration> all = new ArrayList<>(plate());
        // The same geometry quoted again a day later, sliced a little slower.
        all.add(SliceCalibration.of(PLATE_SHA, "plate.stl", CalibrationSource.QUOTE, null, PLA, MEDIUM, 1, 1,
                4400, 1000, PLATE, DAY_TWO.plusSeconds(86400)));

        CalibrationReport report = service.report(all);

        assertThat(report.models()).isEqualTo(1);
        assertThat(report.rows()).isEqualTo(5);
        assertThat(report.sources()).containsEntry("quote", 1L).containsEntry("manual", 4L);
        assertThat(report.timeFactor().get("PETG").get("medium").n()).isEqualTo(1);
        assertThat(report.timeFactor().get("PETG").get("medium").mean()).isCloseTo(5600.0 / 4400, within(0.001));
        assertThat(report.calibratedModels().getFirst().cells()).isEqualTo(4);
    }

    @Test
    void anEmptyTableYieldsZerosAndNullsWithoutExceptions() {
        when(rows.findAllByOrderByMeasuredAtAsc()).thenReturn(List.of());

        CalibrationReport report = service.report();

        assertThat(report.models()).isZero();
        assertThat(report.rows()).isZero();
        assertThat(report.sources()).containsExactly(Map.entry("quote", 0L), Map.entry("order", 0L), Map.entry("manual", 0L));
        assertThat(report.calibratedModels()).isEmpty();
        CalibrationStats stats = report.timeFactor().get("PETG").get("medium");
        assertThat(stats.n()).isZero();
        assertThat(stats.mean()).isNull();
        assertThat(stats.median()).isNull();
        assertThat(stats.min()).isNull();
        assertThat(stats.max()).isNull();
        assertThat(report.lengthFactor().get("PCTG").get("all").n()).isZero();
        assertThat(report.quantity().keySet()).containsExactly("4", "10");
        assertThat(report.quantity().get("4").n()).isZero();
        assertThat(report.quantity().get("4").perCopyRatio().mean()).isNull();
        assertThat(report.quantity().get("4").plateOverheadSeconds().n()).isZero();
        assertThat(report.arithmeticError().get("10").n()).isZero();
        assertThat(report.arithmeticError().get("10").meanAbsPercent()).isNull();
        assertThat(report.configured().plateOverheadSeconds()).isEqualTo(300);
    }

    @Test
    void theCsvCarriesEveryRawRowWithQuotedNames() {
        SliceCalibration row = SliceCalibration.of(PLATE_SHA, "Halter, v2 \"final\".stl", CalibrationSource.ORDER,
                "POD-1000", PETG, HIGH, 1, 1, 5263, 3941, BENCHY, DAY_ONE);
        when(rows.findAllByOrderByMeasuredAtAsc()).thenReturn(List.of(row));

        String csv = service.rowsCsv();

        String[] lines = csv.split("\r\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).startsWith("id,model_sha256,model_name,model_size_bytes,bbox_width_mm")
                .endsWith("source,order_number,measured_at,slicer_version,duration_ms");
        assertThat(lines[1]).contains(row.getId().toString())
                .contains(",\"Halter, v2 \"\"final\"\".stl\",")
                .contains(",PETG,high,1,1,5263,3941.000,")
                .contains(",order,POD-1000,2026-10-22T03:10:00Z,,0");
    }

    @Test
    void theJsonRowsAreTheNewestOfTheTableOrOfOneSource() {
        SliceCalibration newest = SliceCalibration.of(PLATE_SHA, "plate.stl", CalibrationSource.QUOTE, null,
                PLA, MEDIUM, 1, 1, 4000, 1000, PLATE, DAY_TWO);
        SliceCalibration older = SliceCalibration.of(BENCHY_SHA, "benchy.stl", CalibrationSource.ORDER, "POD-1000",
                PETG, HIGH, 1, 1, 5263, 3941, BENCHY, DAY_ONE);
        when(rows.findAllByOrderByMeasuredAtDesc(PageRequest.of(0, 200))).thenReturn(List.of(newest, older));
        when(rows.findAllBySourceOrderByMeasuredAtDesc(CalibrationSource.ORDER, PageRequest.of(0, 2)))
                .thenReturn(List.of(older));

        List<SliceCalibrationRow> all = service.rows(200, null);
        assertThat(all).extracting(SliceCalibrationRow::modelName).containsExactly("plate.stl", "benchy.stl");
        SliceCalibrationRow first = all.getFirst();
        assertThat(first.id()).isEqualTo(newest.getId());
        assertThat(first.measuredAt()).isEqualTo(DAY_TWO);
        assertThat(first.source()).isEqualTo(CalibrationSource.QUOTE);
        assertThat(first.modelSha256()).isEqualTo(PLATE_SHA);
        assertThat(first.bbox()).isEqualTo(PLATE);
        assertThat(first.filament()).isEqualTo("PLA");
        assertThat(first.quality()).isEqualTo("medium");
        assertThat(first.printSeconds()).isEqualTo(4000);
        assertThat(first.filamentLengthMm()).isEqualTo(1000);
        assertThat(first.orderNumber()).isNull();

        List<SliceCalibrationRow> orders = service.rows(2, CalibrationSource.ORDER);
        assertThat(orders).hasSize(1);
        assertThat(orders.getFirst().orderNumber()).isEqualTo("POD-1000");
        assertThat(orders.getFirst().plates()).isEqualTo(1);
    }
}
