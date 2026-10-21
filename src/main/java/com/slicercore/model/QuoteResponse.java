package com.slicercore.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Customer-facing quote for one uploaded model. All prices are in {@code currency}.
 *
 * One copy is sliced; the quantity is derived from it (ADR 0006): material scales linearly,
 * print time counts every copy once plus a fixed overhead per plate, and the plates follow
 * from how many copies of the footprint fit on the bed. The per-unit figures are the totals
 * divided by the quantity.
 *
 * @param color                  the ordered colour; it does not influence the print time, its material
 *                               price may differ from other colours of the filament
 * @param plateCount             plates the batch needs
 * @param printTimeSeconds       effective print time per unit
 * @param totalPrintTimeSeconds  print time for the whole batch, all plates
 * @param filamentLengthMm       filament for the whole batch
 * @param filamentGrams          material per unit
 * @param totalFilamentGrams     material for the whole batch
 * @param machineCostChf         machine cost for the whole batch
 * @param materialCostChf        material cost for the whole batch
 * @param warnings               what the slicer warned about, in its own words; empty when nothing
 * @param manifold               whether the mesh is watertight; null when the slicer could not tell
 */
public record QuoteResponse(
        String fileName,
        String filament,
        ColorOption color,
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
        int leadTimeDays,
        @Schema(description = "Slicer warnings about the model, as the slicer printed them")
        List<String> warnings,
        @Schema(nullable = true, description = "Whether the mesh is watertight; null when the check was not possible")
        Boolean manifold) {

    public QuoteResponse {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
