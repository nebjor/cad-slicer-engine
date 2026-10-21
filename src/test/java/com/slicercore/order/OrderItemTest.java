package com.slicercore.order;

import com.slicercore.model.ColorOption;
import com.slicercore.model.QuoteResponse;
import com.slicercore.support.TestOrders;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.slicercore.support.TestOrders.ANNA;
import static com.slicercore.support.TestOrders.RED;
import static com.slicercore.support.TestOrders.SETTINGS;
import static com.slicercore.support.TestOrders.quote;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An item copies its quote once; the order keeps its items in a gap-free position order. */
class OrderItemTest {

    @Test
    void copiesTheQuoteAndItsColourIncludingTheMaterialPrice() {
        QuoteResponse quote = quote("Mein Teil.stl", "PETG", TestOrders.BLACK, "high", 3, 3.55);

        OrderItem item = OrderItem.create(2, "Mein Teil.stl", quote, TestOrders.storedFile(2));

        assertThat(item.getPosition()).isEqualTo(2);
        assertThat(item.getOriginalFileName()).isEqualTo("Mein Teil.stl");
        assertThat(item.getFileKey()).isEqualTo("123e4567-e89b-12d3-a456-426614174002.stl");
        assertThat(item.getFilament()).isEqualTo("PETG");
        assertThat(item.getColor()).isEqualTo(TestOrders.BLACK);
        assertThat(item.getQuality()).isEqualTo("high");
        assertThat(item.getQualityLabel()).isEqualTo("Fein");
        assertThat(item.getQuantity()).isEqualTo(3);
        assertThat(item.getPrintTimeSeconds()).isEqualTo(6750);
        assertThat(item.getFilamentGrams()).isEqualByComparingTo("34.8");
        assertThat(item.getLayerHeightMm()).isEqualByComparingTo("0.20");
        assertThat(item.getSetupFee()).isEqualByComparingTo("5.00");
        assertThat(item.getUnitPrice()).isEqualByComparingTo("3.55");
        assertThat(item.getLineTotal()).isEqualByComparingTo("15.65");
    }

    @Test
    void copiesTheSlicerWarningsAndTheManifoldCheckAndFlagsWhatNeedsInspection() {
        QuoteResponse clean = quote("x.stl", RED, 1, 3.0);
        QuoteResponse warned = TestOrders.flagged(clean, List.of("Object too small to print"), true);
        QuoteResponse leaky = TestOrders.flagged(clean, List.of(), false);
        QuoteResponse unchecked = TestOrders.flagged(clean, List.of(), null);

        OrderItem cleanItem = OrderItem.create(1, "x.stl", clean, TestOrders.storedFile(1));
        OrderItem warnedItem = OrderItem.create(1, "x.stl", warned, TestOrders.storedFile(1));
        OrderItem leakyItem = OrderItem.create(1, "x.stl", leaky, TestOrders.storedFile(1));
        OrderItem uncheckedItem = OrderItem.create(1, "x.stl", unchecked, TestOrders.storedFile(1));

        assertThat(cleanItem.getWarnings()).isEmpty();
        assertThat(cleanItem.getManifold()).isTrue();
        assertThat(cleanItem.needsInspection()).isFalse();
        assertThat(warnedItem.getWarnings()).containsExactly("Object too small to print");
        assertThat(warnedItem.needsInspection()).isTrue();
        assertThat(leakyItem.getManifold()).isFalse();
        assertThat(leakyItem.needsInspection()).isTrue();
        // Not checked is not the same as failed: the owner is not sent to inspect every old order.
        assertThat(uncheckedItem.getManifold()).isNull();
        assertThat(uncheckedItem.needsInspection()).isFalse();

        assertThat(TestOrders.order("POD-1", SETTINGS, ANNA, List.of(cleanItem)).needsInspection()).isFalse();
        assertThat(TestOrders.order("POD-2", SETTINGS, ANNA, List.of(
                TestOrders.item(1, clean), TestOrders.item(2, leaky))).needsInspection()).isTrue();
    }

    @Test
    void coloursWithoutSwatchOrPriceSurviveTheRoundTrip() {
        ColorOption legacy = new ColorOption("red", "Red", null, null);

        OrderItem item = OrderItem.create(1, "x.stl", quote("x.stl", legacy, 1, 3.0), TestOrders.storedFile(1));

        assertThat(item.getColor()).isEqualTo(legacy);
    }

    @Test
    void unknownQualityCodesFallBackToTheCode() {
        OrderItem item = OrderItem.create(1, "x.stl", quote("x.stl", "PLA", RED, "ultra", 1, 3.0), TestOrders.storedFile(1));

        assertThat(item.getQualityLabel()).isEqualTo("ultra");
    }

    @Test
    void positionsStartAtOneAndRunWithoutGaps() {
        assertThatThrownBy(() -> OrderItem.create(0, "x.stl", quote("x.stl", RED, 1, 3.0), TestOrders.storedFile(1)))
                .isInstanceOf(IllegalArgumentException.class);

        List<OrderItem> gap = List.of(TestOrders.item(1, quote("a.stl", RED, 1, 3.0)), TestOrders.item(3, quote("b.stl", RED, 1, 3.0)));
        assertThatThrownBy(() -> TestOrders.order("POD-1", SETTINGS, ANNA, gap))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gaps");

        Order order = TestOrders.twoItemOrder();
        assertThat(order.getItems()).extracting(OrderItem::getPosition).containsExactly(1, 2);
        assertThat(order.item(2)).map(OrderItem::getOriginalFileName).contains("halter.3mf");
        assertThat(order.item(3)).isEmpty();
        assertThat(order.getItems().get(0).getOrder()).isSameAs(order);
    }

    @Test
    void anItemBelongsToOneOrder() {
        OrderItem shared = TestOrders.item(1, quote("a.stl", RED, 1, 3.0));
        TestOrders.order("POD-1", SETTINGS, ANNA, List.of(shared));

        assertThatThrownBy(() -> TestOrders.order("POD-2", SETTINGS, ANNA, List.of(shared)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POD-1");
    }
}
