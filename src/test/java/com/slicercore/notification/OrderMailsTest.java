package com.slicercore.notification;

import com.slicercore.model.BoundingBox;
import com.slicercore.model.ColorOption;
import com.slicercore.model.QuoteResponse;
import com.slicercore.order.Order;
import com.slicercore.order.OrderFileStorage;
import com.slicercore.order.OrderProperties;
import com.slicercore.order.OrderTotals;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The mail texts are what the customer reads; pin the facts they must carry. */
class OrderMailsTest {

    private static final NotificationProperties PROPS =
            new NotificationProperties(true, "info@3dburg.ch", "owner@3dburg.ch", "3DBurg", "https://3dburg.ch");

    @Test
    void customerConfirmationCarriesNumberConfigurationPriceAndAddress() {
        OrderMails.Mail mail = OrderMails.paidForCustomer(order(false), PROPS);

        assertThat(mail.to()).isEqualTo("anna@example.com");
        assertThat(mail.subject()).isEqualTo("3DBurg – Bestellung POD-1000 bestätigt");
        assertThat(mail.body())
                .contains("Hallo Anna Muster")
                .contains("Bestellnummer: POD-1000")
                .contains("Modell: benchy.stl")
                .contains("Konfiguration: 2 x PLA Red, medium")
                .contains("Druckzeit gesamt: 1 h 15 min")
                .contains("Positionen: CHF 11.00")
                .contains("Versand: CHF 9.00")
                .contains("Gesamt: CHF 20.00")
                .contains("Keine MWST, da nicht MWST-pflichtig.")
                .contains("Bahnhofstrasse 1\n8001 Zürich\nSchweiz")
                .doesNotContain("null");
    }

    @Test
    void vatIsNamedOnceTheBusinessChargesIt() {
        OrderMails.Mail mail = OrderMails.paidForCustomer(order(true), PROPS);

        assertThat(mail.body()).contains("Enthaltene MWST: CHF").doesNotContain("Keine MWST");
    }

    @Test
    void ownerNotificationLinksTheAdminAndNamesTheCustomer() {
        OrderMails.Mail mail = OrderMails.paidForOwner(order(false), PROPS);

        assertThat(mail.to()).isEqualTo("owner@3dburg.ch");
        assertThat(mail.subject()).isEqualTo("Neue Bestellung POD-1000 – CHF 20.00");
        assertThat(mail.body())
                .contains("Anna Muster <anna@example.com>")
                .contains("Admin: https://3dburg.ch/admin");
    }

    @Test
    void shippingNoticeNamesTheOrder() {
        OrderMails.Mail mail = OrderMails.shippedForCustomer(order(false), PROPS);

        assertThat(mail.subject()).isEqualTo("3DBurg – Bestellung POD-1000 ist unterwegs");
        assertThat(mail.body()).contains("wurde heute versendet").contains("Bestellnummer: POD-1000");
    }

    @Test
    void durationsReadNaturally() {
        assertThat(OrderMails.duration(0)).isEqualTo("0 min");
        assertThat(OrderMails.duration(2429)).isEqualTo("40 min");
        assertThat(OrderMails.duration(3599)).isEqualTo("1 h 0 min");
        assertThat(OrderMails.duration(4500)).isEqualTo("1 h 15 min");
    }

    static Order order(boolean vatEnabled) {
        QuoteResponse quote = new QuoteResponse("benchy.stl", "PLA", new ColorOption("red", "Red", "#d62828", null),
                "medium", 2, "A1", 0.2, 1, 2250, 4500, 7761.2, 11.6, 23.2, new BoundingBox(60, 31, 48),
                225154, 5.0, 0.8, 5.0, 3.0, 11.0, "CHF", 3);
        OrderProperties settings = new OrderProperties(new BigDecimal("9.00"), vatEnabled, new BigDecimal("0.081"),
                14, 90, "unused", Set.of("CH"));
        return Order.create("POD-1000", Instant.parse("2026-10-19T11:00:00Z"), 14,
                new Order.Customer("Anna Muster", "anna@example.com", "Bahnhofstrasse 1", "8001", "Zürich", "CH"),
                quote, new OrderFileStorage.StoredFile("123e4567-e89b-12d3-a456-426614174000.stl", "a".repeat(64), 1),
                OrderTotals.from(quote, settings));
    }
}
