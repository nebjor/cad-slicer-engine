package com.slicercore.service;

import com.slicercore.config.QuotePricingProperties;
import com.slicercore.model.BoundingBox;
import com.slicercore.model.FilamentType;
import com.slicercore.model.PrintQuality;
import com.slicercore.model.QuoteResponse;
import com.slicercore.model.SliceQuoteResult;
import org.springframework.stereotype.Service;

/**
 * Turns a slicer result into a customer price.
 *
 * Mass is derived from the extruded filament length and the material density, because the
 * slicer reports {@code total_used_g = 0} for most models (feasibility note O3).
 */
@Service
public class QuotePricingService {

    private final QuotePricingProperties pricing;

    public QuotePricingService(QuotePricingProperties pricing) {
        this.pricing = pricing;
    }

    public QuoteResponse price(SliceQuoteResult slice, String displayFileName, String machineProfile,
                               FilamentType filament, PrintQuality quality, int quantity) {
        double printHours = slice.totalPredicationSeconds() / 3600.0;
        double filamentGrams = gramsFromLength(slice.filamentLengthMm(), filament);

        double materialPricePerKg = pricing.materialPriceChfPerKg().getOrDefault(filament, 0.0);
        double machineCost = printHours * pricing.machineRateChfPerHour();
        double materialCost = filamentGrams / 1000.0 * materialPricePerKg;
        double unitPrice = Math.max(pricing.minUnitPriceChf(), machineCost + materialCost);
        double totalPrice = pricing.setupFeeChf() + unitPrice * quantity;

        int leadTimeDays = leadTimeDays(printHours * quantity, quality);

        BoundingBox bbox = slice.boundingBox();
        return new QuoteResponse(
                displayFileName,
                filament.name(),
                quality.code(),
                quantity,
                machineProfile,
                round(slice.layerHeightMm() > 0 ? slice.layerHeightMm() : quality.layerHeightMm(), 2),
                round(slice.totalPredicationSeconds(), 0),
                round(slice.filamentLengthMm(), 1),
                round(filamentGrams, 1),
                new BoundingBox(round(bbox.widthMm(), 1), round(bbox.depthMm(), 1), round(bbox.heightMm(), 1)),
                slice.triangleCount(),
                roundToFiveRappen(machineCost),
                roundToFiveRappen(materialCost),
                roundToFiveRappen(pricing.setupFeeChf()),
                roundToFiveRappen(unitPrice),
                roundToFiveRappen(totalPrice),
                pricing.currency(),
                leadTimeDays);
    }

    double gramsFromLength(double filamentLengthMm, FilamentType filament) {
        double radiusMm = pricing.filamentDiameterMm() / 2.0;
        double volumeMm3 = Math.PI * radiusMm * radiusMm * filamentLengthMm;
        double volumeCm3 = volumeMm3 / 1000.0;
        return volumeCm3 * filament.densityGramsPerCm3();
    }

    /** Base lead time of the quality tier plus one day per full printer-day the order occupies. */
    int leadTimeDays(double totalPrintHours, PrintQuality quality) {
        int productionDays = (int) Math.ceil(totalPrintHours / pricing.printHoursPerDay());
        return quality.baseLeadTimeDays() + Math.max(0, productionDays - 1);
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    /** Swiss cash rounding: nearest 0.05. */
    private static double roundToFiveRappen(double value) {
        return Math.round(value * 20.0) / 20.0;
    }
}
