package com.slicercore.model;

/**
 * Customer-facing quote for one uploaded model. All prices are in {@code currency}.
 *
 * The whole quantity is sliced as one batch, arranged over as many plates as needed, so the
 * totals reflect batch printing; the per-unit figures are the totals divided by the quantity.
 *
 * @param plateCount             plates the batch occupies
 * @param printTimeSeconds       effective print time per unit
 * @param totalPrintTimeSeconds  print time for the whole batch, all plates
 * @param filamentLengthMm       filament for the whole batch
 * @param filamentGrams          material per unit
 * @param totalFilamentGrams     material for the whole batch
 * @param machineCostChf         machine cost for the whole batch
 * @param materialCostChf        material cost for the whole batch
 */
public record QuoteResponse(
        String fileName,
        String filament,
        String quality,
        int quantity,
        String machineProfile,
        double layerHeightMm,
        int plateCount,
        double printTimeSeconds,
        double totalPrintTimeSeconds,
        double filamentLengthMm,
        double filamentGrams,
        double totalFilamentGrams,
        BoundingBox boundingBoxMm,
        int triangleCount,
        double machineCostChf,
        double materialCostChf,
        double setupFeeChf,
        double unitPriceChf,
        double totalPriceChf,
        String currency,
        int leadTimeDays) {
}
