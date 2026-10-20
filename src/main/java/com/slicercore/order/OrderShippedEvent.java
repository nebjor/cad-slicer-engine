package com.slicercore.order;

/** Published inside the transaction that moves the order to SHIPPED; consumers run after commit. */
public record OrderShippedEvent(Order order) {
}
