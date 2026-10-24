package com.slicercore.order;

/** An order was posted without a live account session; ordering needs an account (ADR 0011). */
public class OrderLoginRequiredException extends RuntimeException {

    public OrderLoginRequiredException() {
        super("Not logged in.");
    }
}
