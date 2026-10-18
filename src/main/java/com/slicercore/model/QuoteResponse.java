package com.slicercore.model;

/** Customer-facing quote for one uploaded model. All prices are in {@code currency}. */
public record QuoteResponse(
        String fileName,
        String filament,
        String quality,
        int quantity,
        String machineProfile,
        double layerHeightMm,
        double printTimeSeconds,
        double filamentLengthMm,
        double filamentGrams,
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
