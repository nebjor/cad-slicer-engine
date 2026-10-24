package com.slicercore.account;

/** The order does not exist or does not belong to the signed-in account. */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException() {
        super("Order not found.");
    }
}
