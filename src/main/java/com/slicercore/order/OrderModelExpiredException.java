package com.slicercore.order;

/**
 * An item referenced a stored model that is unknown or has expired. Nothing was stored; the
 * client re-uploads that one model and retries with its new id.
 */
public class OrderModelExpiredException extends RuntimeException {

    private final int itemIndex;

    public OrderModelExpiredException(int itemIndex) {
        super("The model of item " + (itemIndex + 1) + " is unknown or has expired; upload it again.");
        this.itemIndex = itemIndex;
    }

    /** 0-based index into the request's items. */
    public int itemIndex() {
        return itemIndex;
    }
}
