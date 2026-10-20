package com.slicercore.order;

import com.slicercore.model.BoundingBox;
import com.slicercore.model.ColorOption;
import com.slicercore.model.QuoteResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Money on orders is BigDecimal, cash-rounded; these pin the arithmetic and the VAT switch. */
class OrderTotalsTest {

    private static final OrderProperties NO_VAT = new OrderProperties(
            new BigDecimal("9.00"), false, new BigDecimal("0.081"), 14, 90, "./data", Set.of("CH"));
    private static final OrderProperties WITH_VAT = new OrderProperties(
            new BigDecimal("9.00"), true, new BigDecimal("0.081"), 14, 90, "./data", Set.of("CH"));

    @Test
    void addsFlatShippingAndCarriesNoVatWhenNotRegistered() {
        OrderTotals totals = OrderTotals.from(quote(10, 3.0, 35.0), NO_VAT);

        assertThat(totals.setupFee()).isEqualByComparingTo("5.00");
        assertThat(totals.unitPrice()).isEqualByComparingTo("3.00");
        assertThat(totals.itemsTotal()).isEqualByComparingTo("35.00");
        assertThat(totals.shipping()).isEqualByComparingTo("9.00");
        assertThat(totals.total()).isEqualByComparingTo("44.00");
        assertThat(totals.vatRate()).isEqualByComparingTo("0");
        assertThat(totals.vatAmount()).isEqualByComparingTo("0.00");
        assertThat(totals.total().scale()).isEqualTo(2);
        assertThat(totals.currency()).isEqualTo("CHF");
    }

    @Test
    void carvesInclusiveVatOutOfTheTotalWhenRegistered() {
        OrderTotals totals = OrderTotals.from(quote(10, 3.0, 35.0), WITH_VAT);

        // 44.00 inclusive at 8.1%: net 40.70, VAT 3.30 (cash-rounded).
        assertThat(totals.total()).isEqualByComparingTo("44.00");
        assertThat(totals.vatRate()).isEqualByComparingTo("0.0810");
        assertThat(totals.vatAmount()).isEqualByComparingTo("3.30");
    }

    @Test
    void roundsToFiveRappen() {
        assertThat(Chf.of(4.2222)).isEqualByComparingTo("4.20");
        assertThat(Chf.of(4.2250)).isEqualByComparingTo("4.25");
        assertThat(Chf.of(4.2749)).isEqualByComparingTo("4.25");
        assertThat(Chf.of(4.2750)).isEqualByComparingTo("4.30");
        assertThat(Chf.round(new BigDecimal("0.01"))).isEqualByComparingTo("0.00");
        assertThat(Chf.round(new BigDecimal("0.03"))).isEqualByComparingTo("0.05");
    }

    @Test
    void itemsTotalIsRecomputedFromRoundedParts() {
        // 3 x 4.70 + 5.00 = 19.10; the quote's own double total is not trusted.
        OrderTotals totals = OrderTotals.from(quote(3, 4.7, 99.99), NO_VAT);

        assertThat(totals.itemsTotal()).isEqualByComparingTo("19.10");
        assertThat(totals.total()).isEqualByComparingTo("28.10");
    }

    private static QuoteResponse quote(int quantity, double unitPrice, double total) {
        return new QuoteResponse("benchy.stl", "PLA", new ColorOption("black", "Black", "#1f1f1f", null), "medium",
                quantity, "A1", 0.2, 1, 2239, 2239.0 * quantity, 3880.6 * quantity, 11.6, 11.6 * quantity,
                new BoundingBox(60, 31, 48), 225154, 2.5 * quantity, 0.4 * quantity, 5.0, unitPrice, total,
                "CHF", 3);
    }
}
