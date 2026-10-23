package com.slicercore.quote;

import com.slicercore.slicer.PrintQuality;
import com.slicercore.slicer.FilamentType;
import com.slicercore.slicer.BoundingBox;
import com.slicercore.config.QuotePricingProperties;
import com.slicercore.slicer.SliceQuoteResult;
import com.slicercore.slicer.SlicingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;

/**
 * Turns a single-copy slicer result into a customer price for any filament and quantity
 * (ADR 0006, ADR 0007).
 *
 * The slice covers one copy of the reference filament (PLA) on one plate. The chosen filament
 * scales it by two measured factors first: its print time relative to PLA and its extruded
 * length relative to PLA ({@code quote.material.*}). Material then scales linearly with the
 * quantity. Print time does not: every plate carries a fixed part (heating, purge line, first
 * layer) once, and each further copy on the same plate only adds its own time. The number of
 * plates comes from how many copies of the model's footprint fit on the bed. Mass is derived
 * from the extruded filament length and the material density, because the slicer reports
 * {@code total_used_g = 0} for most models (feasibility note O3).
 */
@Service
public class QuotePricingService {

    private static final Logger log = LoggerFactory.getLogger(QuotePricingService.class);

    /** A copy never counts for less than this, however small the model. */
    static final double MIN_PER_COPY_SECONDS = 60;
    /** Nor for less than this share of its own slice; guards against an overhead larger than the slice. */
    static final double MIN_PER_COPY_SHARE = 0.25;
    /** What a filament without a configured factor is scaled by: nothing. */
    static final double DEFAULT_FACTOR = 1.0;

    private final QuotePricingProperties pricing;

    public QuotePricingService(QuotePricingProperties pricing) {
        this.pricing = pricing;
        checkFactors("quote.material.time-factor", pricing.material().timeFactor());
        checkFactors("quote.material.length-factor", pricing.material().lengthFactor());
    }

    /** A filament without a factor is priced like the reference; a non-positive factor is a misconfiguration. */
    private static void checkFactors(String property, Map<FilamentType, Double> factors) {
        for (FilamentType filament : FilamentType.values()) {
            Double factor = factors.get(filament);
            if (factor == null) {
                log.warn("{} has no entry for {}; its slices are priced with factor {}", property, filament, DEFAULT_FACTOR);
            } else if (!(factor > 0)) {
                throw new IllegalArgumentException(property + " for " + filament + " must be positive, was " + factor);
            }
        }
    }

    public QuoteResponse price(SliceQuoteResult slice, String displayFileName, String machineProfile,
                               FilamentType filament, ColorOption color, PrintQuality quality, int quantity) {
        BoundingBox bbox = slice.boundingBox();
        int plates = plates(quantity, copiesPerPlate(bbox));

        // The measured time ratios compare whole single-copy prints, overhead included, so the
        // factor applies to the whole slice; the overhead subtracted per plate stays the
        // configured constant (a PETG bed heats a little longer, which the factor absorbs).
        double singleCopySeconds = slice.totalPredicationSeconds() * timeFactor(filament);
        double totalSeconds = batchPrintSeconds(singleCopySeconds, bbox, quantity);
        double totalHours = totalSeconds / 3600.0;

        double filamentLengthMm = slice.filamentLengthMm() * lengthFactor(filament);
        double gramsPerCopy = gramsFromLength(filamentLengthMm, filament);
        double totalGrams = quantity * gramsPerCopy;

        double materialPricePerKg = color != null && color.pricePerKgChf() != null
                ? color.pricePerKgChf()
                : pricing.materialPriceChfPerKg().getOrDefault(filament, 0.0);
        double machineCost = totalHours * pricing.machineRateChfPerHour();
        double materialCost = totalGrams / 1000.0 * materialPricePerKg;
        double unitPrice = Math.max(pricing.minUnitPriceChf(), (machineCost + materialCost) / quantity);
        double totalPrice = unitPrice * quantity;
        // The plate overhead is shared by every copy on the plate, so a batch is cheaper per piece
        // than a single print. The single-piece price lets the shop show that as a discount.
        double singleMachineCost = batchPrintSeconds(singleCopySeconds, bbox, 1) / 3600.0 * pricing.machineRateChfPerHour();
        double singleMaterialCost = gramsPerCopy / 1000.0 * materialPricePerKg;
        double unitPriceSingle = Math.max(pricing.minUnitPriceChf(), singleMachineCost + singleMaterialCost);

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
                round(quantity * filamentLengthMm, 1),
                round(gramsPerCopy, 1),
                round(totalGrams, 1),
                new BoundingBox(round(bbox.widthMm(), 1), round(bbox.depthMm(), 1), round(bbox.heightMm(), 1)),
                slice.triangleCountPerCopy(),
                roundToFiveRappen(machineCost),
                roundToFiveRappen(materialCost),
                roundToFiveRappen(unitPrice),
                roundToFiveRappen(unitPriceSingle),
                roundToFiveRappen(totalPrice),
                pricing.currency(),
                leadTimeDays,
                slice.warnings(),
                slice.manifold());
    }

    /**
     * The quantity arithmetic of ADR 0006 on its own: print time of {@code quantity} copies
     * from the single-copy time and the footprint, every copy at the slice minus the plate
     * overhead, plus that overhead once per plate. The calibration (ADR 0008) compares this
     * prediction with real batch slices, so it must stay the formula the price uses.
     */
    public double batchPrintSeconds(double singleCopySeconds, BoundingBox footprint, int quantity) {
        int plates = plates(quantity, copiesPerPlate(footprint));
        double perCopySeconds = perCopySeconds(singleCopySeconds);
        double plateOverheadSeconds = singleCopySeconds - perCopySeconds;
        return quantity * perCopySeconds + plates * plateOverheadSeconds;
    }

    /** Print time of the filament relative to the reference slice; 1.0 when not configured. */
    public double timeFactor(FilamentType filament) {
        return pricing.material().timeFactor().getOrDefault(filament, DEFAULT_FACTOR);
    }

    /** Extruded length of the filament relative to the reference slice; 1.0 when not configured. */
    public double lengthFactor(FilamentType filament) {
        return pricing.material().lengthFactor().getOrDefault(filament, DEFAULT_FACTOR);
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

    /** Mass of an extruded length: the filament's cross-section times its density. */
    public double gramsFromLength(double filamentLengthMm, FilamentType filament) {
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
