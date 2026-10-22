package com.slicercore.cart;

import java.util.List;

/** The signed-in customer's cart, in display order. */
public record CartResponse(List<CartItemResponse> items) {
}
