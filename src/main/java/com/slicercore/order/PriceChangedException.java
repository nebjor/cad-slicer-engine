package com.slicercore.order;

import com.slicercore.model.QuoteResponse;

/**
 * The price recomputed at checkout differs from the one the customer saw, for example after a
 * rate change. The customer must confirm the new quote before an order is created.
 */
public class PriceChangedException extends RuntimeException {

    private final transient QuoteResponse currentQuote;

    public PriceChangedException(QuoteResponse currentQuote) {
        super("The price has changed since the quote was shown.");
        this.currentQuote = currentQuote;
    }

    public QuoteResponse currentQuote() {
        return currentQuote;
    }
}
