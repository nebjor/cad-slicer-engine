package com.slicercore.cart;

/** The cart belongs to an account; no live session came with the request. */
public class CartNotLoggedInException extends RuntimeException {

    public CartNotLoggedInException() {
        super("Not logged in.");
    }
}
