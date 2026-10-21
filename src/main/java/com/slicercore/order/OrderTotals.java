package com.slicercore.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The money on an order: the sum of its item lines, flat shipping charged once, and the total.
 * Computed once from the items and the commercial settings and then stored verbatim. Pure
 * arithmetic in {@link BigDecimal}; the per-item figures live in {@link ItemTotals}.
 *
 * VAT, when enabled, is included in the prices shown (Swiss practice), so the VAT amount is
 * carved out of the total rather than added on top.
 */
public record OrderTotals(
        BigDecimal itemsTotal,
        BigDecimal shipping,
        BigDecimal vatRate,
        BigDecimal vatAmount,
        BigDecimal total,
        String currency) {

    public static OrderTotals from(List<ItemTotals> items, String currency, OrderProperties settings) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one item.");
        }
        BigDecimal itemsTotal = Chf.round(items.stream()
                .map(ItemTotals::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal shipping = Chf.round(settings.shippingFlatChf());
        BigDecimal total = Chf.round(itemsTotal.add(shipping));

        BigDecimal vatRate = settings.vatEnabled() ? settings.vatRate().setScale(4, RoundingMode.HALF_UP) : zeroRate();
        BigDecimal vatAmount = settings.vatEnabled() ? includedVat(total, vatRate) : Chf.round(BigDecimal.ZERO);

        return new OrderTotals(itemsTotal, shipping, vatRate, vatAmount, total, currency);
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
