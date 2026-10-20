package com.slicercore.order;

/** Published inside the transaction that marks the order paid; consumers run after commit. */
public record OrderPaidEvent(Order order) {
}
