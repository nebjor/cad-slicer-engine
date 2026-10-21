package com.slicercore.config;

import com.slicercore.model.FilamentType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Pricing inputs for quotes, bound from the {@code quote.*} block in application.yml.
 *
 * @param currency              ISO currency code shown to customers
 * @param setupFeeChf           flat fee charged once per quoted model
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
 */
@ConfigurationProperties(prefix = "quote")
public record QuotePricingProperties(
        String currency,
        double setupFeeChf,
        double machineRateChfPerHour,
        double minUnitPriceChf,
        double printHoursPerDay,
        double filamentDiameterMm,
        int maxQuantity,
        double plateOverheadSeconds,
        Plate plate,
        Map<FilamentType, Double> materialPriceChfPerKg) {

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
}
