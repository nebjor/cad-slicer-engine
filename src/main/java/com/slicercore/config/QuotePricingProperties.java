package com.slicercore.config;

import com.slicercore.model.FilamentType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Pricing inputs for quotes, bound from the {@code quote.*} block in application.yml.
 *
 * @param currency              ISO currency code shown to customers
 * @param machineRateChfPerHour cost of printer time
 * @param minUnitPriceChf       floor for the per-piece price (machine + material)
 * @param printHoursPerDay      printer hours available per day, drives the lead time
 * @param filamentDiameterMm    filament diameter used to turn extruded length into volume
 * @param maxQuantity           largest quantity quoted in one request; the quantity is
 *                              derived from a single-copy slice, so this bounds the order
 *                              size, not the slicer
 * @param plateOverheadSeconds  fixed print time every plate carries once (heating, purge
 *                              line, first layer); subtracted from the single-copy slice to
 *                              get the time of one more copy on the same plate
 * @param plate                 build plate the copies are packed onto
 * @param materialPriceChfPerKg material price per filament type
 * @param material              how each filament scales the reference slice (ADR 0007)
 */
@ConfigurationProperties(prefix = "quote")
public record QuotePricingProperties(
        String currency,
        double machineRateChfPerHour,
        double minUnitPriceChf,
        double printHoursPerDay,
        double filamentDiameterMm,
        int maxQuantity,
        double plateOverheadSeconds,
        Plate plate,
        Map<FilamentType, Double> materialPriceChfPerKg,
        Material material) {

    public QuotePricingProperties {
        material = material == null ? Material.UNSCALED : material;
    }

    /**
     * Printable area of the build plate and how the slicer's arrange spaces parts on it.
     *
     * @param widthMm  bed size along X
     * @param depthMm  bed size along Y
     * @param marginMm edge of the bed kept free on every side
     * @param gapMm    distance kept between neighbouring parts
     */
    public record Plate(double widthMm, double depthMm, double marginMm, double gapMm) {
    }

    /**
     * Per-filament factors applied to the single-copy slice of the reference filament (PLA)
     * before the quantity arithmetic. A filament without an entry is priced like the
     * reference, factor 1.0.
     *
     * @param timeFactor   print time of the filament relative to the reference slice
     * @param lengthFactor extruded filament length relative to the reference slice
     */
    public record Material(Map<FilamentType, Double> timeFactor, Map<FilamentType, Double> lengthFactor) {

        /** Every filament priced like the reference slice. */
        public static final Material UNSCALED = new Material(Map.of(), Map.of());

        public Material {
            timeFactor = timeFactor == null ? Map.of() : Map.copyOf(timeFactor);
            lengthFactor = lengthFactor == null ? Map.of() : Map.copyOf(lengthFactor);
        }
    }
}
