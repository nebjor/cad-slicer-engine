package com.slicercore.order;

import com.slicercore.model.ColorOption;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * One item of an order as the customer sees it. Print time and material are per line, that
 * is for the whole batch of {@code quantity} copies of this item, not per copy.
 *
 * @param printTimeSeconds print time of the whole line (all copies of this item)
 * @param filamentGrams    material of the whole line (all copies of this item)
 */
public record OrderItemResponse(
        @Schema(description = "1-based position on the order") int position,
        String fileName,
        String filament,
        ColorOption color,
        String quality,
        int quantity,
        BigDecimal unitPriceChf,
        @Schema(description = "Charged once per item") BigDecimal setupFeeChf,
        @Schema(description = "setupFeeChf + unitPriceChf x quantity") BigDecimal lineTotalChf,
        @Schema(description = "Print time of the whole line, all copies of this item") int printTimeSeconds,
        @Schema(description = "Material of the whole line, all copies of this item") BigDecimal filamentGrams) {

    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getPosition(),
                item.getOriginalFileName(),
                item.getFilament(),
                item.getColor(),
                item.getQuality(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getSetupFee(),
                item.getLineTotal(),
                item.getPrintTimeSeconds(),
                item.getFilamentGrams());
    }
}
