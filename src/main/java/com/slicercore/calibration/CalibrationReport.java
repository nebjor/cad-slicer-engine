package com.slicercore.calibration;

import com.slicercore.catalog.BoundingBox;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What the calibration rows say about the pricing constants (ADR 0008). Every statistic is
 * over models, one value per model and cell: a model that was ordered three times still
 * counts once. Factor maps are keyed by filament name, then by quality code or {@code all};
 * quantity maps by the number of copies as a string.
 *
 * @param models          calibrated models (distinct geometries with at least one row)
 * @param rows            raw rows in the table
 * @param sources         rows per origin: {@code quote} (the quote path's own slices),
 *                        {@code order} and {@code manual} (the matrix the owner asked for)
 * @param configured      the constants the quote uses today, for comparison
 * @param timeFactor      measured print time relative to the PLA slice, per filament and quality
 * @param lengthFactor    measured extruded length relative to the PLA slice
 * @param quantity        how a real batch compares with the single copy, per batch size
 * @param arithmeticError the quantity arithmetic's deviation from the real batch, per batch size
 * @param calibratedModels one entry per model, newest measurement first
 */
public record CalibrationReport(
        int models,
        long rows,
        Map<String, Long> sources,
        Configured configured,
        Map<String, Map<String, CalibrationStats>> timeFactor,
        Map<String, Map<String, CalibrationStats>> lengthFactor,
        Map<String, QuantityStats> quantity,
        Map<String, ArithmeticError> arithmeticError,
        List<CalibratedModel> calibratedModels) {

    /** The constants in {@code quote.material.*} and {@code quote.plate-overhead-seconds}, reference filament excluded. */
    public record Configured(Map<String, Double> timeFactor, Map<String, Double> lengthFactor,
                             double plateOverheadSeconds) {
    }

    /** Statistics over the models that have the cell; all four figures are null when none has. */
    public record CalibrationStats(
            int n,
            @Schema(nullable = true) Double mean,
            @Schema(nullable = true) Double median,
            @Schema(nullable = true) Double min,
            @Schema(nullable = true) Double max) {

        public static final CalibrationStats EMPTY = new CalibrationStats(0, null, null, null, null);

        public static CalibrationStats of(List<Double> values) {
            if (values.isEmpty()) {
                return EMPTY;
            }
            List<Double> sorted = values.stream().sorted().toList();
            double sum = 0;
            for (double value : sorted) {
                sum += value;
            }
            int size = sorted.size();
            double median = size % 2 == 1
                    ? sorted.get(size / 2)
                    : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
            return new CalibrationStats(size, round(sum / size), round(median),
                    round(sorted.getFirst()), round(sorted.getLast()));
        }
    }

    /**
     * @param n                    models with both the single copy and this batch
     * @param perCopyRatio         (t(N) / N) / t(1): below 1 when a plate's overhead is shared
     * @param plateOverheadSeconds (N * t(1) - t(N)) / (N - 1), only where the batch fit one plate
     */
    public record QuantityStats(int n, CalibrationStats perCopyRatio, CalibrationStats plateOverheadSeconds) {
    }

    /**
     * Deviation of the quantity arithmetic (ADR 0006, the configured overhead and plate) from
     * the measured batch, in percent of the measured time; positive means the quote is high.
     */
    public record ArithmeticError(
            int n,
            @Schema(nullable = true) Double meanPercent,
            @Schema(nullable = true) Double meanAbsPercent,
            @Schema(nullable = true) Double maxAbsPercent) {

        public static final ArithmeticError EMPTY = new ArithmeticError(0, null, null, null);

        public static ArithmeticError of(List<Double> percents) {
            if (percents.isEmpty()) {
                return EMPTY;
            }
            double sum = 0;
            double absSum = 0;
            double absMax = 0;
            for (double percent : percents) {
                sum += percent;
                absSum += Math.abs(percent);
                absMax = Math.max(absMax, Math.abs(percent));
            }
            return new ArithmeticError(percents.size(), round(sum / percents.size()),
                    round(absSum / percents.size()), round(absMax));
        }
    }

    /** One calibrated geometry; nothing personal, the order number is the only link to an order. */
    public record CalibratedModel(
            String modelName,
            String modelSha256,
            CalibrationSource source,
            @Schema(nullable = true) String orderNumber,
            Instant measuredAt,
            int cells,
            int triangleCount,
            BoundingBox bbox) {
    }

    static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
