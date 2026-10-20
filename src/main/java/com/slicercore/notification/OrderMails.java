package com.slicercore.notification;

import com.slicercore.order.Order;
import com.slicercore.order.OrderTotals;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The texts of the order mails. Plain text on purpose: it renders everywhere, survives spam
 * filters better than HTML and is easy to diff in a test. Customer-facing text is German.
 */
final class OrderMails {

    private static final Map<String, String> COUNTRY_NAMES = Map.of("CH", "Schweiz");

    private OrderMails() {
    }

    record Mail(String to, String subject, String body) {
    }

    static Mail paidForCustomer(Order order, NotificationProperties props) {
        String subject = props.shopName() + " – Bestellung " + order.getOrderNumber() + " bestätigt";
        String body = """
                Hallo %s

                Danke für deine Bestellung bei %s. Die Zahlung ist eingegangen. Wir prüfen dein Modell \
                und starten danach den Druck.

                %s
                Lieferadresse:
                %s
                Fragen zur Bestellung? Antworte einfach auf diese E-Mail.

                Freundliche Grüsse
                %s
                """.formatted(order.getCustomerName(), props.shopName(), orderSummary(order), address(order),
                props.shopName());
        return new Mail(order.getCustomerEmail(), subject, body);
    }

    static Mail paidForOwner(Order order, NotificationProperties props) {
        OrderTotals totals = order.getTotals();
        String subject = "Neue Bestellung " + order.getOrderNumber() + " – " + money(totals.currency(), totals.total());
        String body = """
                Bezahlte Bestellung %s von %s <%s>.

                %s
                Lieferadresse:
                %s
                Admin: %s/admin
                """.formatted(order.getOrderNumber(), order.getCustomerName(), order.getCustomerEmail(),
                orderSummary(order), address(order), props.shopUrl());
        return new Mail(props.ownerEmail(), subject, body);
    }

    static Mail shippedForCustomer(Order order, NotificationProperties props) {
        String subject = props.shopName() + " – Bestellung " + order.getOrderNumber() + " ist unterwegs";
        String body = """
                Hallo %s

                Deine Bestellung %s wurde heute versendet und ist in den nächsten Tagen bei dir.

                %s
                Fragen zur Lieferung? Antworte einfach auf diese E-Mail.

                Freundliche Grüsse
                %s
                """.formatted(order.getCustomerName(), order.getOrderNumber(), orderSummary(order), props.shopName());
        return new Mail(order.getCustomerEmail(), subject, body);
    }

    private static String orderSummary(Order order) {
        OrderTotals totals = order.getTotals();
        StringBuilder text = new StringBuilder()
                .append("Bestellnummer: ").append(order.getOrderNumber()).append('\n')
                .append("Modell: ").append(order.getOriginalFileName()).append('\n')
                .append("Konfiguration: ").append(order.getQuantity()).append(" x ").append(order.getFilament())
                .append(' ').append(order.getColorLabel()).append(", ").append(order.getQuality()).append('\n')
                .append("Druckzeit gesamt: ").append(duration(order.getTotalPrintTimeSeconds())).append('\n')
                .append('\n')
                .append("Positionen: ").append(money(totals.currency(), totals.itemsTotal())).append('\n')
                .append("Versand: ").append(money(totals.currency(), totals.shipping())).append('\n')
                .append("Gesamt: ").append(money(totals.currency(), totals.total())).append('\n');
        if (totals.vatAmount().signum() == 0) {
            text.append("Keine MWST, da nicht MWST-pflichtig.\n");
        } else {
            text.append("Enthaltene MWST: ").append(money(totals.currency(), totals.vatAmount())).append('\n');
        }
        return text.toString();
    }

    private static String address(Order order) {
        return order.getCustomerName() + '\n'
                + order.getStreet() + '\n'
                + order.getPostalCode() + ' ' + order.getCity() + '\n'
                + COUNTRY_NAMES.getOrDefault(order.getCountry(), order.getCountry()) + '\n';
    }

    static String money(String currency, BigDecimal amount) {
        return currency + ' ' + amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    static String duration(int seconds) {
        int hours = seconds / 3600;
        int minutes = Math.round((seconds % 3600) / 60f);
        if (minutes == 60) {
            hours++;
            minutes = 0;
        }
        return hours > 0 ? hours + " h " + minutes + " min" : minutes + " min";
    }
}
