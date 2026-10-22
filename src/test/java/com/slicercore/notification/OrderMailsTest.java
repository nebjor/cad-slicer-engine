package com.slicercore.notification;

import com.slicercore.order.Order;
import com.slicercore.order.OrderProperties;
import com.slicercore.support.TestOrders;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The mail texts are what the customer reads; pin the facts they must carry. */
class OrderMailsTest {

    private static final NotificationProperties PROPS =
            new NotificationProperties(true, "info@3dburg.ch", "owner@3dburg.ch", "3DBurg", "https://3dburg.ch");

    @Test
    void customerConfirmationListsEveryItemWithPriceShippingTotalAndAddress() {
        OrderMails.Mail mail = OrderMails.paidForCustomer(order(false), PROPS);

        assertThat(mail.to()).isEqualTo("anna@example.com");
        assertThat(mail.subject()).isEqualTo("3DBurg – Bestellung POD-1000 bestätigt");
        assertThat(mail.body())
                .contains("Hallo Anna Muster")
                .contains("Bestellnummer: POD-1000")
                .contains("Positionen:\n"
                        + "  1. benchy.stl: 2 x PLA Red, Standard – CHF 6.00\n"
                        + "  2. halter.3mf: 1 x PETG Black, Fein – CHF 3.00\n")
                .contains("Druckzeit gesamt: 1 h 53 min")
                .contains("Zwischensumme: CHF 9.00")
                .contains("Versand: CHF 9.00")
                .contains("Gesamt: CHF 23.00")
                .contains("Keine MWST, da nicht MWST-pflichtig.")
                .contains("Bahnhofstrasse 1\n8001 Zürich\nSchweiz")
                .contains("Bestellung ansehen: https://3dburg.ch/bestellung?nr=POD-1000&email=anna%40example.com")
                .doesNotContain("null");
    }

    @Test
    void vatIsNamedOnceTheBusinessChargesIt() {
        OrderMails.Mail mail = OrderMails.paidForCustomer(order(true), PROPS);

        assertThat(mail.body()).contains("Enthaltene MWST: CHF").doesNotContain("Keine MWST");
    }

    @Test
    void ownerNotificationLinksTheAdminNamesTheCustomerAndListsTheItems() {
        OrderMails.Mail mail = OrderMails.paidForOwner(order(false), PROPS);

        assertThat(mail.to()).isEqualTo("owner@3dburg.ch");
        assertThat(mail.subject()).isEqualTo("Neue Bestellung POD-1000 – CHF 23.00");
        assertThat(mail.body())
                .contains("Anna Muster <anna@example.com>")
                .contains("2. halter.3mf: 1 x PETG Black, Fein – CHF 3.00")
                .contains("Admin: https://3dburg.ch/admin");
    }

    @Test
    void shippingNoticeNamesTheOrderAndItsItems() {
        OrderMails.Mail mail = OrderMails.shippedForCustomer(order(false), PROPS);

        assertThat(mail.subject()).isEqualTo("3DBurg – Bestellung POD-1000 ist unterwegs");
        assertThat(mail.body())
                .contains("wurde heute versendet")
                .contains("Bestellnummer: POD-1000")
                .contains("1. benchy.stl: 2 x PLA Red, Standard – CHF 6.00")
                .contains("Gesamt: CHF 23.00");
    }

    @Test
    void durationsReadNaturally() {
        assertThat(OrderMails.duration(0)).isEqualTo("0 min");
        assertThat(OrderMails.duration(2429)).isEqualTo("40 min");
        assertThat(OrderMails.duration(3599)).isEqualTo("1 h 0 min");
        assertThat(OrderMails.duration(4500)).isEqualTo("1 h 15 min");
    }

    /** Two items, 6.00 and 3.00, setup fee 5.00, shipping 9.00: total 23.00; 4500 s plus 2250 s of printing. */
    static Order order(boolean vatEnabled) {
        OrderProperties settings = new OrderProperties(new BigDecimal("9.00"), new BigDecimal("5.00"), new BigDecimal("3.00"), vatEnabled, new BigDecimal("0.081"),
                14, 90, "unused", Set.of("CH"));
        return TestOrders.twoItemOrder(settings);
    }
}
