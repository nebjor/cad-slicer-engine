package com.slicercore.order;

import com.slicercore.model.QuoteResponse;

import java.math.BigDecimal;

/**
 * The money of one order item, computed once from its quote and then stored verbatim. The
 * line total is the unit price times the quantity, cash-rounded; the setup fee is charged
 * once per order and lives in {@link OrderTotals}. The quote's doubles are converted exactly
 * once, here.
 */
public record ItemTotals(BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {

    public static ItemTotals from(QuoteResponse quote) {
        BigDecimal unitPrice = Chf.of(quote.unitPriceChf());
        BigDecimal lineTotal = Chf.round(unitPrice.multiply(BigDecimal.valueOf(quote.quantity())));
        return new ItemTotals(unitPrice, quote.quantity(), lineTotal);
    }
}
