package com.slicercore.account;

/** Too many order lookups from one account or address within a minute. */
public class OrderLookupLimitException extends RuntimeException {

    public OrderLookupLimitException() {
        super("Too many requests. Please wait a moment and try again.");
    }
}
