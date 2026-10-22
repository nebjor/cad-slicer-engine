package com.slicercore.cart;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

/**
 * One line of the cart as the client wants it. Exactly one of {@code id} and {@code modelId}
 * names the file: {@code id} keeps the file already stored for that line, {@code modelId}
 * copies a model from the one-hour upload store.
 *
 * @param id       a line of this account's current cart whose stored file is kept
 * @param modelId  the id {@code POST /api/models} returned; unknown or expired gives 404 {@code model-expired}
 * @param fileName what to call the model; shown again on every device
 * @param colorId  the colour code the quote used
 * @param quote    the quote the frontend holds for the line, stored as is and returned as is
 */
public record CartItemRequest(
        @Schema(nullable = true) UUID id,
        @Schema(nullable = true) String modelId,
        @NotBlank @Size(max = 255) String fileName,
        @NotBlank @Size(max = 32) String filament,
        @NotBlank @Size(max = 64) String colorId,
        @NotBlank @Size(max = 32) String quality,
        @Min(1) @Max(100) int quantity,
        @NotNull @Schema(types = "object", description = "Opaque quote JSON of the line") Map<String, Object> quote) {

    AccountCartItem.Choice choice() {
        String base = fileName.trim().replace('\\', '/');
        return new AccountCartItem.Choice(base.substring(base.lastIndexOf('/') + 1), filament.trim(), colorId.trim(),
                quality.trim(), quantity, quote);
    }
}
