package com.slicercore.order;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of an order. Transitions are explicit so that an illegal one, such as shipping an
 * unpaid order, is rejected rather than silently stored.
 *
 * The review and approval steps are separate on purpose: later, a reviewer role will only see
 * orders in {@link #IN_REVIEW}, and a printer role only {@link #APPROVED} onwards.
 */
public enum OrderStatus {
    /** Placed, awaiting payment. */
    NEW,
    /** Payment confirmed by the provider's webhook. */
    PAID,
    /** The owner checks printability before committing material. */
    IN_REVIEW,
    /** Reviewed and released for printing. */
    APPROVED,
    PRINTING,
    SHIPPED,
    CANCELLED;

    public Set<OrderStatus> allowedTransitions() {
        return switch (this) {
            case NEW -> EnumSet.of(PAID, CANCELLED);
            case PAID -> EnumSet.of(IN_REVIEW, CANCELLED);
            case IN_REVIEW -> EnumSet.of(APPROVED, CANCELLED);
            case APPROVED -> EnumSet.of(PRINTING, CANCELLED);
            case PRINTING -> EnumSet.of(SHIPPED);
            case SHIPPED, CANCELLED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canTransitionTo(OrderStatus next) {
        return allowedTransitions().contains(next);
    }
}
