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
        Map<FilamentType, Double> materialPriceChfPerKg) {
}
