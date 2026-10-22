package com.slicercore.cart;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A stored cart line; its file is brought into the upload store by {@code POST /api/account/cart/{id}/model}. */
public record CartItemResponse(
        UUID id,
        int position,
        String fileName,
        long sizeBytes,
        String filament,
        String colorId,
        String quality,
        int quantity,
        @Schema(types = "object", description = "The quote JSON the client saved with the line") Map<String, Object> quote,
        Instant updatedAt) {

    static CartItemResponse from(AccountCartItem item) {
        return new CartItemResponse(item.getId(), item.getPosition(), item.getFileName(), item.getSizeBytes(),
                item.getFilament(), item.getColorId(), item.getQuality(), item.getQuantity(), item.getQuote(),
                item.getUpdatedAt());
    }
}
