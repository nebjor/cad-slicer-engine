package com.slicercore.order;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Checkout request: the customer, the items as references to models kept by
 * {@code POST /api/models}, and the total the customer saw. The backend re-quotes every
 * item itself; {@code expectedTotalChf} only lets it refuse silently different prices.
 */
public record OrderRequest(
        @NotNull @Valid CustomerDetails customer,
        @NotNull @Size(min = 1, max = OrderRequest.MAX_ITEMS) List<@Valid OrderItemRequest> items,
        @Schema(description = "The items total the customer saw: the sum of the item quotes' totalPriceChf, "
                + "shipping excluded. Optional; when present and different from the recomputed items total "
                + "the order is refused with 409 price-changed.",
                nullable = true)
        BigDecimal expectedTotalChf) {

    public static final int MAX_ITEMS = 10;
}
