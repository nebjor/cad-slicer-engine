package com.slicercore.calibration;

import com.slicercore.calibration.CalibrationReport.ArithmeticError;
import com.slicercore.calibration.CalibrationReport.CalibratedModel;
import com.slicercore.calibration.CalibrationReport.CalibrationStats;
import com.slicercore.calibration.CalibrationReport.Configured;
import com.slicercore.calibration.CalibrationReport.QuantityStats;
import com.slicercore.config.QuotePricingProperties;
import com.slicercore.model.FilamentType;
import com.slicercore.model.PrintQuality;
import com.slicercore.model.SlicingException;
import com.slicercore.service.QuotePricingService;
import com.slicercore.service.QuoteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Turns the calibration rows into the figures the owner compares with {@code application.yml}
 * (ADR 0008). Per model only the latest row of each cell counts; statistics run over models.
 */
@Service
public class CalibrationReportService {

    private static final Logger log = LoggerFactory.getLogger(CalibrationReportService.class);
    private static final FilamentType REFERENCE = QuoteService.REFERENCE_FILAMENT;
    private static final String ALL_QUALITIES = "all";
    private static final String CSV_HEADER = String.join(",",
            "id", "model_sha256", "model_name", "model_size_bytes", "bbox_width_mm", "bbox_depth_mm", "bbox_height_mm",
            "triangle_count", "filament", "quality", "copies", "plates", "print_seconds", "filament_length_mm",
            "filament_grams", "warnings_count", "manifold", "source", "order_number", "measured_at", "slicer_version",
            "duration_ms");

    private final SliceCalibrationRepository rows;
    private final QuotePricingService pricing;
    private final QuotePricingProperties properties;

    public CalibrationReportService(SliceCalibrationRepository rows, QuotePricingService pricing,
                                    QuotePricingProperties properties) {
        this.rows = rows;
        this.pricing = pricing;
        this.properties = properties;
    }

    /** One cell of the matrix as a map key. */
    record Cell(FilamentType filament, PrintQuality quality, int copies) {
    }

    @Transactional(readOnly = true)
    public CalibrationReport report() {
        List<SliceCalibration> all = rows.findAllByOrderByMeasuredAtAsc();
        return report(all);
    }

    /** The report over the given rows; the latest row of a cell replaces earlier ones. */
    CalibrationReport report(List<SliceCalibration> all) {
        Map<String, Map<Cell, SliceCalibration>> byModel = new LinkedHashMap<>();
        for (SliceCalibration row : all) {
            Cell cell;
            try {
                cell = new Cell(FilamentType.fromInput(row.getFilament()), PrintQuality.fromInput(row.getQuality()),
                        row.getCopies());
            } catch (IllegalArgumentException e) {
                log.warn("Calibration row {} names an unknown filament or quality and is ignored: {}", row.getId(),
                        e.getMessage());
                continue;
            }
            byModel.computeIfAbsent(row.getModelSha256(), key -> new LinkedHashMap<>()).put(cell, row);
        }

        Map<String, Map<String, CalibrationStats>> timeFactor = new LinkedHashMap<>();
        Map<String, Map<String, CalibrationStats>> lengthFactor = new LinkedHashMap<>();
        for (FilamentType filament : FilamentType.values()) {
            if (filament == REFERENCE) {
                continue;
            }
            timeFactor.put(filament.name(), factors(byModel, filament, SliceCalibration::getPrintSeconds));
            lengthFactor.put(filament.name(), factors(byModel, filament, SliceCalibration::getFilamentLengthMm));
        }

        Map<String, QuantityStats> quantity = new LinkedHashMap<>();
        Map<String, ArithmeticError> arithmeticError = new LinkedHashMap<>();
        for (int copies : batchSizes(byModel)) {
            List<Double> ratios = new ArrayList<>();
            List<Double> overheads = new ArrayList<>();
            List<Double> errors = new ArrayList<>();
            for (Map<Cell, SliceCalibration> cells : byModel.values()) {
                SliceCalibration single = cells.get(new Cell(REFERENCE, CalibrationRunner.BATCH_QUALITY, 1));
                SliceCalibration batch = cells.get(new Cell(REFERENCE, CalibrationRunner.BATCH_QUALITY, copies));
                if (single == null || batch == null || single.getPrintSeconds() <= 0 || batch.getPrintSeconds() <= 0) {
                    continue;
                }
                double t1 = single.getPrintSeconds();
                double tN = batch.getPrintSeconds();
                ratios.add((tN / copies) / t1);
                if (batch.getPlates() == 1) {
                    overheads.add((copies * t1 - tN) / (copies - 1));
                }
                try {
                    double predicted = pricing.batchPrintSeconds(t1, single.boundingBox(), copies);
                    errors.add((predicted - tN) / tN * 100.0);
                } catch (SlicingException e) {
                    log.warn("Model {} does not fit the configured plate; its batch is not compared: {}",
                            single.getModelName(), e.getMessage());
                }
            }
            quantity.put(String.valueOf(copies),
                    new QuantityStats(ratios.size(), CalibrationStats.of(ratios), CalibrationStats.of(overheads)));
            arithmeticError.put(String.valueOf(copies), ArithmeticError.of(errors));
        }

        List<CalibratedModel> models = byModel.values().stream()
                .map(CalibrationReportService::summarize)
                .sorted(Comparator.comparing(CalibratedModel::measuredAt).reversed())
                .toList();

        return new CalibrationReport(byModel.size(), all.size(), configured(), timeFactor, lengthFactor,
                quantity, arithmeticError, models);
    }

    /** The filament's ratio to the reference at each quality, one value per model, plus all pooled. */
    private static Map<String, CalibrationStats> factors(Map<String, Map<Cell, SliceCalibration>> byModel,
                                                         FilamentType filament, Measure measure) {
        Map<String, CalibrationStats> perQuality = new LinkedHashMap<>();
        List<Double> pooled = new ArrayList<>();
        for (PrintQuality quality : PrintQuality.values()) {
            List<Double> values = new ArrayList<>();
            for (Map<Cell, SliceCalibration> cells : byModel.values()) {
                SliceCalibration reference = cells.get(new Cell(REFERENCE, quality, 1));
                SliceCalibration other = cells.get(new Cell(filament, quality, 1));
                if (reference == null || other == null || measure.of(reference) <= 0) {
                    continue;
                }
                values.add(measure.of(other) / measure.of(reference));
            }
            perQuality.put(quality.code(), CalibrationStats.of(values));
            pooled.addAll(values);
        }
        perQuality.put(ALL_QUALITIES, CalibrationStats.of(pooled));
        return perQuality;
    }

    @FunctionalInterface
    private interface Measure {
        double of(SliceCalibration row);
    }

    private static TreeSet<Integer> batchSizes(Map<String, Map<Cell, SliceCalibration>> byModel) {
        TreeSet<Integer> sizes = new TreeSet<>(CalibrationRunner.BATCH_COPIES);
        for (Map<Cell, SliceCalibration> cells : byModel.values()) {
            for (Cell cell : cells.keySet()) {
                if (cell.copies() > 1) {
                    sizes.add(cell.copies());
                }
            }
        }
        return sizes;
    }

    private static CalibratedModel summarize(Map<Cell, SliceCalibration> cells) {
        SliceCalibration representative = cells.getOrDefault(new Cell(REFERENCE, CalibrationRunner.BATCH_QUALITY, 1),
                cells.values().iterator().next());
        SliceCalibration newest = cells.values().stream()
                .max(Comparator.comparing(SliceCalibration::getMeasuredAt))
                .orElseThrow();
        return new CalibratedModel(representative.getModelName(), representative.getModelSha256(),
                representative.getSource(), representative.getOrderNumber(), newest.getMeasuredAt(), cells.size(),
                representative.getTriangleCount(), representative.boundingBox());
    }

    private Configured configured() {
        Map<String, Double> time = new TreeMap<>();
        Map<String, Double> length = new TreeMap<>();
        for (FilamentType filament : FilamentType.values()) {
            if (filament == REFERENCE) {
                continue;
            }
            time.put(filament.name(), pricing.timeFactor(filament));
            length.put(filament.name(), pricing.lengthFactor(filament));
        }
        return new Configured(time, length, properties.plateOverheadSeconds());
    }

    /** Every raw row, newest last, as the table holds it. */
    @Transactional(readOnly = true)
    public String rowsCsv() {
        StringBuilder csv = new StringBuilder(CSV_HEADER).append("\r\n");
        for (SliceCalibration row : rows.findAllByOrderByMeasuredAtAsc()) {
            csv.append(String.join(",",
                    String.valueOf(row.getId()),
                    row.getModelSha256(),
                    csvField(row.getModelName()),
                    String.valueOf(row.getModelSizeBytes()),
                    decimal(row.getBboxWidthMm()),
                    decimal(row.getBboxDepthMm()),
                    decimal(row.getBboxHeightMm()),
                    String.valueOf(row.getTriangleCount()),
                    row.getFilament(),
                    row.getQuality(),
                    String.valueOf(row.getCopies()),
                    String.valueOf(row.getPlates()),
                    String.valueOf(row.getPrintSeconds()),
                    decimal(row.getFilamentLengthMm()),
                    decimal(row.getFilamentGrams()),
                    String.valueOf(row.getWarningsCount()),
                    row.getManifold() == null ? "" : String.valueOf(row.getManifold()),
                    row.getSource().code(),
                    row.getOrderNumber() == null ? "" : row.getOrderNumber(),
                    String.valueOf(row.getMeasuredAt()),
                    csvField(row.getSlicerVersion() == null ? "" : row.getSlicerVersion()),
                    String.valueOf(row.getDurationMs()))).append("\r\n");
        }
        return csv.toString();
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String csvField(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
