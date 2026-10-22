package com.slicercore.admin;

import com.slicercore.model.ColorOption;
import com.slicercore.order.OrderItem;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * One item as the owner sees it: the customer's view plus the file and slice details needed
 * to review and print it. The model downloads at
 * {@code GET /api/admin/orders/{orderNumber}/items/{position}/model}.
 */
public record AdminOrderItemResponse(
        @Schema(description = "1-based position on the order") int position,
        String fileName,
        long fileSizeBytes,
        String fileSha256,
        @Schema(description = "Slicer warnings about the model, as the slicer printed them") List<String> warnings,
        @Schema(nullable = true, description = "Whether the mesh is watertight; null when not checked") Boolean manifold,
        String filament,
        ColorOption color,
        String quality,
        int quantity,
        String machineProfile,
        BigDecimal layerHeightMm,
        int plateCount,
        int printTimeSeconds,
        BigDecimal filamentGrams,
        BigDecimal unitPriceChf,
        BigDecimal lineTotalChf) {

    public static AdminOrderItemResponse from(OrderItem item) {
        return new AdminOrderItemResponse(
                item.getPosition(),
                item.getOriginalFileName(),
                item.getFileSizeBytes(),
                item.getFileSha256(),
                item.getWarnings(),
                item.getManifold(),
                item.getFilament(),
                item.getColor(),
                item.getQuality(),
                item.getQuantity(),
                item.getMachineProfile(),
                item.getLayerHeightMm(),
                item.getPlateCount(),
                item.getPrintTimeSeconds(),
                item.getFilamentGrams(),
                item.getUnitPrice(),
                item.getLineTotal());
    }
}
