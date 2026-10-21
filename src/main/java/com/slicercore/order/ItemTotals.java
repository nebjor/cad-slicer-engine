package com.slicercore.order;

import com.slicercore.model.QuoteResponse;

import java.math.BigDecimal;

/**
 * The money of one order item, computed once from its quote and then stored verbatim. Every
 * item is its own model and its own slice, so the setup fee is charged per item; the line
 * total is the setup fee plus the unit price times the quantity, cash-rounded. The quote's
 * doubles are converted exactly once, here.
 */
public record ItemTotals(BigDecimal setupFee, BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {

    public static ItemTotals from(QuoteResponse quote) {
        BigDecimal setupFee = Chf.of(quote.setupFeeChf());
        BigDecimal unitPrice = Chf.of(quote.unitPriceChf());
        BigDecimal lineTotal = Chf.round(setupFee.add(unitPrice.multiply(BigDecimal.valueOf(quote.quantity()))));
        return new ItemTotals(setupFee, unitPrice, quote.quantity(), lineTotal);
    }
}
