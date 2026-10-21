package com.slicercore.order;

import com.slicercore.model.QuoteResponse;

import java.util.List;

/**
 * The price recomputed at checkout differs from the one the customer saw, for example after a
 * rate change. The customer must confirm the new quotes before an order is created.
 */
public class PriceChangedException extends RuntimeException {

    private final transient List<QuoteResponse> currentQuotes;
    private final transient OrderTotals currentTotals;

    public PriceChangedException(List<QuoteResponse> currentQuotes, OrderTotals currentTotals) {
        super("The price has changed since the quote was shown.");
        this.currentQuotes = List.copyOf(currentQuotes);
        this.currentTotals = currentTotals;
    }

    /** Fresh quotes, one per requested item in request order. */
    public List<QuoteResponse> currentQuotes() {
        return currentQuotes;
    }

    public OrderTotals currentTotals() {
        return currentTotals;
    }
}
