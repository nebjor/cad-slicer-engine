package com.slicercore.service;

import com.slicercore.config.QuotePricingProperties;
import com.slicercore.model.BoundingBox;
import com.slicercore.model.ColorOption;
import com.slicercore.model.FilamentType;
import com.slicercore.model.PrintQuality;
import com.slicercore.model.QuoteResponse;
import com.slicercore.model.SliceQuoteResult;
import com.slicercore.model.SlicingException;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Turns a single-copy slicer result into a customer price for any quantity (ADR 0006).
 *
 * The slice covers one copy on one plate. Material scales linearly with the quantity. Print
 * time does not: every plate carries a fixed part (heating, purge line, first layer) once,
 * and each further copy on the same plate only adds its own time. The number of plates comes
 * from how many copies of the model's footprint fit on the bed. Mass is derived from the
 * extruded filament length and the material density, because the slicer reports
 * {@code total_used_g = 0} for most models (feasibility note O3).
 */
@Service
public class QuotePricingService {

    /** A copy never counts for less than this, however small the model. */
    static final double MIN_PER_COPY_SECONDS = 60;
    /** Nor for less than this share of its own slice; guards against an overhead larger than the slice. */
    static final double MIN_PER_COPY_SHARE = 0.25;

    private final QuotePricingProperties pricing;

    public QuotePricingService(QuotePricingProperties pricing) {
        this.pricing = pricing;
    }

    public QuoteResponse price(SliceQuoteResult slice, String displayFileName, String machineProfile,
                               FilamentType filament, ColorOption color, PrintQuality quality, int quantity) {
        BoundingBox bbox = slice.boundingBox();
        int plates = plates(quantity, copiesPerPlate(bbox));

        double singleCopySeconds = slice.totalPredicationSeconds();
        double perCopySeconds = perCopySeconds(singleCopySeconds);
        double plateOverheadSeconds = singleCopySeconds - perCopySeconds;
        double totalSeconds = quantity * perCopySeconds + plates * plateOverheadSeconds;
        double totalHours = totalSeconds / 3600.0;

        double gramsPerCopy = gramsFromLength(slice.filamentLengthMm(), filament);
        double totalGrams = quantity * gramsPerCopy;

        double materialPricePerKg = color != null && color.pricePerKgChf() != null
                ? color.pricePerKgChf()
                : pricing.materialPriceChfPerKg().getOrDefault(filament, 0.0);
        double machineCost = totalHours * pricing.machineRateChfPerHour();
        double materialCost = totalGrams / 1000.0 * materialPricePerKg;
        double unitPrice = Math.max(pricing.minUnitPriceChf(), (machineCost + materialCost) / quantity);
        double totalPrice = pricing.setupFeeChf() + unitPrice * quantity;

        int leadTimeDays = leadTimeDays(totalHours, quality);

        return new QuoteResponse(
                displayFileName,
                filament.name(),
                color,
                quality.code(),
                quantity,
                machineProfile,
                round(slice.layerHeightMm() > 0 ? slice.layerHeightMm() : quality.layerHeightMm(), 2),
                plates,
                round(totalSeconds / quantity, 0),
                round(totalSeconds, 0),
                round(quantity * slice.filamentLengthMm(), 1),
                round(gramsPerCopy, 1),
                round(totalGrams, 1),
                new BoundingBox(round(bbox.widthMm(), 1), round(bbox.depthMm(), 1), round(bbox.heightMm(), 1)),
                slice.triangleCountPerCopy(),
                roundToFiveRappen(machineCost),
                roundToFiveRappen(materialCost),
                roundToFiveRappen(pricing.setupFeeChf()),
                roundToFiveRappen(unitPrice),
                roundToFiveRappen(totalPrice),
                pricing.currency(),
                leadTimeDays,
                slice.warnings(),
                slice.manifold());
    }

    /**
     * Print time one more copy adds to a plate: the single-copy slice minus the configured
     * plate overhead, floored at {@link #MIN_PER_COPY_SECONDS} or {@link #MIN_PER_COPY_SHARE}
     * of the slice, whichever is larger, and never above the slice itself. The last bound
     * keeps a quantity of one at exactly the slice's own time, floor or not.
     */
    double perCopySeconds(double singleCopySeconds) {
        double floor = Math.max(MIN_PER_COPY_SECONDS, MIN_PER_COPY_SHARE * singleCopySeconds);
        double withoutOverhead = singleCopySeconds - pricing.plateOverheadSeconds();
        return Math.min(singleCopySeconds, Math.max(floor, withoutOverhead));
    }

    /**
     * How many copies of the footprint the slicer's arrange can pack on the bed: a grid in
     * whichever of the two orientations fits more, with the edge margin kept free and the
     * arrange gap between parts. Real packing of odd shapes may differ by a copy or two.
     */
    int copiesPerPlate(BoundingBox box) {
        QuotePricingProperties.Plate plate = pricing.plate();
        int upright = fitAlong(plate.widthMm(), box.widthMm()) * fitAlong(plate.depthMm(), box.depthMm());
        int rotated = fitAlong(plate.widthMm(), box.depthMm()) * fitAlong(plate.depthMm(), box.widthMm());
        int copies = Math.max(upright, rotated);
        if (copies < 1) {
            throw new SlicingException(String.format(Locale.ROOT,
                    "Model is larger than the build plate (%.0f x %.0f mm with a %.0f mm margin): %.1f x %.1f mm.",
                    plate.widthMm(), plate.depthMm(), plate.marginMm(), box.widthMm(), box.depthMm()));
        }
        return copies;
    }

    /** Parts along one axis: the usable length plus one gap, divided by a part plus its gap. */
    private int fitAlong(double bedMm, double partMm) {
        QuotePricingProperties.Plate plate = pricing.plate();
        double usableMm = bedMm - 2 * plate.marginMm();
        return (int) Math.floor((usableMm + plate.gapMm()) / (partMm + plate.gapMm()));
    }

    static int plates(int quantity, int copiesPerPlate) {
        return (quantity + copiesPerPlate - 1) / copiesPerPlate;
    }

    double gramsFromLength(double filamentLengthMm, FilamentType filament) {
        double radiusMm = pricing.filamentDiameterMm() / 2.0;
        double volumeMm3 = Math.PI * radiusMm * radiusMm * filamentLengthMm;
        double volumeCm3 = volumeMm3 / 1000.0;
        return volumeCm3 * filament.densityGramsPerCm3();
    }

    /** Base lead time of the quality tier plus one day per full printer-day the batch occupies. */
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
