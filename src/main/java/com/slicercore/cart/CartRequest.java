package com.slicercore.cart;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** The whole cart, in display order. An empty list empties the cart. */
public record CartRequest(
        @NotNull @Size(max = AccountCartService.MAX_ITEMS) List<@Valid @NotNull CartItemRequest> items) {
}
