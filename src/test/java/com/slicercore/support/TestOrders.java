package com.slicercore.support;

import com.slicercore.model.BoundingBox;
import com.slicercore.model.ColorOption;
import com.slicercore.model.QuoteResponse;
import com.slicercore.order.Order;
import com.slicercore.order.OrderFileStorage;
import com.slicercore.order.OrderItem;
import com.slicercore.order.OrderProperties;
import com.slicercore.order.OrderTotals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/** Orders for tests: the same Benchy-like numbers everywhere, so totals are easy to check by hand. */
public final class TestOrders {

    public static final OrderProperties SETTINGS = new OrderProperties(
            new BigDecimal("9.00"), false, new BigDecimal("0.081"), 14, 90, "unused", Set.of("CH"));
    public static final Order.Customer ANNA = new Order.Customer(
            "Anna Muster", "anna@example.com", "Bahnhofstrasse 1", "8001", "Zürich", "CH");
    public static final ColorOption RED = new ColorOption("red", "Red", "#d62828", 36.0);
    public static final ColorOption BLACK = new ColorOption("black", "Black", "#1f1f1f", 45.0);
    public static final Instant PLACED_AT = Instant.parse("2026-10-19T11:00:00Z");

    private TestOrders() {
    }

    /** A PLA quote at standard quality: CHF 5.00 setup plus the unit price, 37.5 min and 11.6 g per copy. */
    public static QuoteResponse quote(String fileName, ColorOption color, int quantity, double unitPrice) {
        return quote(fileName, "PLA", color, "medium", quantity, unitPrice);
    }

    public static QuoteResponse quote(String fileName, String filament, ColorOption color, String quality,
                                      int quantity, double unitPrice) {
        return new QuoteResponse(fileName, filament, color, quality, quantity, "Bambu Lab A1 0.4 nozzle", 0.2, 1,
                2250, 2250.0 * quantity, 3880.6 * quantity, 11.6, 11.6 * quantity, new BoundingBox(60, 31, 48),
                225154, 2.5 * quantity, 0.4 * quantity, 5.0, unitPrice, 5.0 + unitPrice * quantity, "CHF", 3);
    }

    /** A plausible stored file for the given position; the key never contains a client name. */
    public static OrderFileStorage.StoredFile storedFile(int position) {
        return new OrderFileStorage.StoredFile("123e4567-e89b-12d3-a456-42661417400" + position + ".stl",
                String.valueOf((char) ('a' + position - 1)).repeat(64), 1000L * position);
    }

    public static OrderItem item(int position, QuoteResponse quote) {
        return OrderItem.create(position, quote.fileName(), quote, storedFile(position));
    }

    public static Order order(String orderNumber, OrderProperties settings, Order.Customer customer,
                              QuoteResponse... quotes) {
        List<OrderItem> items = IntStream.range(0, quotes.length).mapToObj(i -> item(i + 1, quotes[i])).toList();
        return order(orderNumber, settings, customer, items);
    }

    public static Order order(String orderNumber, OrderProperties settings, Order.Customer customer,
                              List<OrderItem> items) {
        OrderTotals totals = OrderTotals.from(items.stream().map(OrderItem::getTotals).toList(), "CHF", settings);
        return Order.create(orderNumber, PLACED_AT, settings.quoteValidityDays(), customer, items, totals);
    }

    /** POD-1000 with one item, 2 x benchy.stl in red at CHF 3.00: line 11.00, total 20.00. */
    public static Order benchyOrder() {
        return order("POD-1000", SETTINGS, ANNA, quote("benchy.stl", RED, 2, 3.0));
    }

    /**
     * POD-1000 with two items: 2 x benchy.stl, PLA red, standard (11.00) and 1 x halter.3mf, PETG
     * black, fine (8.00): items 19.00, shipping 9.00, total 28.00.
     */
    public static Order twoItemOrder() {
        return twoItemOrder(SETTINGS);
    }

    public static Order twoItemOrder(OrderProperties settings) {
        return order("POD-1000", settings, ANNA,
                quote("benchy.stl", RED, 2, 3.0),
                quote("halter.3mf", "PETG", BLACK, "high", 1, 3.0));
    }
}
