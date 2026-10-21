package com.slicercore.order;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One model to order. The print parameters repeat the quote request so the backend recomputes
 * the price itself.
 *
 * @param modelId  the id {@code POST /api/models} returned; an unknown or expired id is answered
 *                 with 404 {@code model-expired} and the item's index
 * @param fileName what to call the model on the order; blank means the name it was uploaded under
 */
public record OrderItemRequest(
        @NotBlank String modelId,
        @Schema(nullable = true) @Size(max = 255) String fileName,
        @NotBlank String filament,
        @Schema(nullable = true) String color,
        @NotBlank String quality,
        @Min(1) @Max(100) int quantity) {
}
