package com.slicercore.order;

import com.slicercore.model.QuoteResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The money on an order, computed once from the quote and the commercial settings and then
 * stored verbatim. Pure arithmetic in {@link BigDecimal}; the quote's doubles are converted
 * exactly once at the boundary.
 *
 * VAT, when enabled, is included in the prices shown (Swiss practice), so the VAT amount is
 * carved out of the total rather than added on top.
 */
public record OrderTotals(
        BigDecimal setupFee,
        BigDecimal unitPrice,
        BigDecimal itemsTotal,
        BigDecimal shipping,
        BigDecimal vatRate,
        BigDecimal vatAmount,
        BigDecimal total,
        String currency) {

    public static OrderTotals from(QuoteResponse quote, OrderProperties settings) {
        BigDecimal setupFee = Chf.of(quote.setupFeeChf());
        BigDecimal unitPrice = Chf.of(quote.unitPriceChf());
        BigDecimal itemsTotal = Chf.round(setupFee.add(unitPrice.multiply(BigDecimal.valueOf(quote.quantity()))));
        BigDecimal shipping = Chf.round(settings.shippingFlatChf());
        BigDecimal total = Chf.round(itemsTotal.add(shipping));

        BigDecimal vatRate = settings.vatEnabled() ? settings.vatRate().setScale(4, RoundingMode.HALF_UP) : zeroRate();
        BigDecimal vatAmount = settings.vatEnabled() ? includedVat(total, vatRate) : Chf.round(BigDecimal.ZERO);

        return new OrderTotals(setupFee, unitPrice, itemsTotal, shipping, vatRate, vatAmount, total, quote.currency());
    }

    /** Share of an inclusive total that is VAT: total - total / (1 + rate). */
    static BigDecimal includedVat(BigDecimal inclusiveTotal, BigDecimal rate) {
        BigDecimal net = inclusiveTotal.divide(BigDecimal.ONE.add(rate), 10, RoundingMode.HALF_UP);
        return Chf.round(inclusiveTotal.subtract(net));
    }

    private static BigDecimal zeroRate() {
        return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    }
}
