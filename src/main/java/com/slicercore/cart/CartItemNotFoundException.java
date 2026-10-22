package com.slicercore.cart;

/** A cart line id that is not (or no longer) in this account's cart. */
public class CartItemNotFoundException extends RuntimeException {

    public CartItemNotFoundException() {
        super("That cart line does not exist.");
    }
}
