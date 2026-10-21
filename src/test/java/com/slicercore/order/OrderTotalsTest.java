package com.slicercore.order;

import com.slicercore.support.TestOrders;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static com.slicercore.support.TestOrders.RED;
import static com.slicercore.support.TestOrders.quote;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Money on orders is BigDecimal, cash-rounded; these pin the arithmetic across items and the VAT switch. */
class OrderTotalsTest {

    private static final OrderProperties NO_VAT = TestOrders.SETTINGS;
    private static final OrderProperties WITH_VAT = new OrderProperties(
            new BigDecimal("9.00"), true, new BigDecimal("0.081"), 14, 90, "./data", Set.of("CH"));

    @Test
    void addsFlatShippingAndCarriesNoVatWhenNotRegistered() {
        ItemTotals line = ItemTotals.from(quote("benchy.stl", RED, 10, 3.0));
        OrderTotals totals = OrderTotals.from(List.of(line), "CHF", NO_VAT);

        assertThat(line.setupFee()).isEqualByComparingTo("5.00");
        assertThat(line.unitPrice()).isEqualByComparingTo("3.00");
        assertThat(line.lineTotal()).isEqualByComparingTo("35.00");
        assertThat(totals.itemsTotal()).isEqualByComparingTo("35.00");
        assertThat(totals.shipping()).isEqualByComparingTo("9.00");
        assertThat(totals.total()).isEqualByComparingTo("44.00");
        assertThat(totals.vatRate()).isEqualByComparingTo("0");
        assertThat(totals.vatAmount()).isEqualByComparingTo("0.00");
        assertThat(totals.total().scale()).isEqualTo(2);
        assertThat(totals.currency()).isEqualTo("CHF");
    }

    @Test
    void setupFeeIsChargedPerItemAndShippingOncePerOrder() {
        List<ItemTotals> lines = List.of(
                ItemTotals.from(quote("benchy.stl", RED, 2, 3.0)),
                ItemTotals.from(quote("halter.stl", RED, 1, 3.0)),
                ItemTotals.from(quote("deckel.stl", RED, 4, 3.55)));

        OrderTotals totals = OrderTotals.from(lines, "CHF", NO_VAT);

        // (5 + 6) + (5 + 3) + (5 + 14.20) = 38.20, shipping once.
        assertThat(lines).extracting(ItemTotals::lineTotal)
                .map(BigDecimal::toPlainString).containsExactly("11.00", "8.00", "19.20");
        assertThat(totals.itemsTotal()).isEqualByComparingTo("38.20");
        assertThat(totals.shipping()).isEqualByComparingTo("9.00");
        assertThat(totals.total()).isEqualByComparingTo("47.20");
    }

    @Test
    void carvesInclusiveVatOutOfTheTotalWhenRegistered() {
        OrderTotals totals = OrderTotals.from(List.of(ItemTotals.from(quote("benchy.stl", RED, 10, 3.0))), "CHF", WITH_VAT);

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
    void lineTotalIsRecomputedFromRoundedParts() {
        // 3 x 4.70 + 5.00 = 19.10; the quote's own double total is not trusted.
        ItemTotals line = ItemTotals.from(quote("benchy.stl", RED, 3, 4.7));
        OrderTotals totals = OrderTotals.from(List.of(line), "CHF", NO_VAT);

        assertThat(line.lineTotal()).isEqualByComparingTo("19.10");
        assertThat(totals.total()).isEqualByComparingTo("28.10");
    }

    @Test
    void anOrderWithoutItemsHasNoPrice() {
        assertThatThrownBy(() -> OrderTotals.from(List.of(), "CHF", NO_VAT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
