package com.slicercore.order;

/**
 * Something another module does once an order has been placed and saved, such as queueing
 * its models for the slice calibration (ADR 0008). A port like {@link RetentionSweep}:
 * implementations are picked up as beans and the order module never learns who listens.
 *
 * Called inside the transaction that saves the order. A listener that throws is logged and
 * the order stands, so an implementation that writes to the database must do so in a
 * transaction of its own ({@code REQUIRES_NEW}); a failure inside the order's transaction
 * would mark it for rollback and cost the customer the order after all.
 */
public interface OrderPlacedListener {

    void orderPlaced(Order order);
}
