package com.slicercore.payment;

import com.slicercore.order.Order;
import com.slicercore.order.OrderItem;
import com.slicercore.order.PaymentGateway;
import com.slicercore.order.PaymentSession;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Starts a Stripe Checkout session for an order: one line item per order item plus one for the
 * flat shipping, amounts in rappen, the order number as reference, and the shop's success page
 * as both return targets. The customer pays on Stripe's page; the session becomes a paid order
 * only through {@link StripeWebhookController}.
 */
@Component
@ConditionalOnProperty(name = "payments.provider", havingValue = PaymentProperties.STRIPE)
public class StripeCheckoutGateway implements PaymentGateway {

    static final String SETUP_FEE_LINE_NAME = "Einrichtung";
    static final String SHIPPING_LINE_NAME = "Versand (Pauschale)";

    private final PaymentProperties properties;
    private final CheckoutSessions sessions;

    public StripeCheckoutGateway(PaymentProperties properties, CheckoutSessions sessions) {
        this.properties = properties;
        this.sessions = sessions;
    }

    @Override
    public PaymentSession start(Order order) {
        try {
            Session session = sessions.create(checkoutParams(order));
            return new PaymentSession(PaymentProperties.STRIPE, session.getId(), session.getUrl());
        } catch (StripeException e) {
            throw new IllegalStateException("Stripe could not start the payment for order "
                    + order.getOrderNumber() + ".", e);
        }
    }

    SessionCreateParams checkoutParams(Order order) {
        String successPage = properties.publicBaseUrl() + "/checkout/success?order=" + encode(order.getOrderNumber());
        String currency = order.getTotals().currency().toLowerCase();

        SessionCreateParams.Builder params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setLocale(SessionCreateParams.Locale.DE)
                .setClientReferenceId(order.getOrderNumber())
                .putMetadata("orderNumber", order.getOrderNumber())
                .setCustomerEmail(order.getCustomerEmail())
                .setSuccessUrl(successPage)
                .setCancelUrl(successPage + "&cancelled=1");
        for (OrderItem item : order.getItems()) {
            params.addLineItem(lineItem(item, currency));
        }
        if (order.getTotals().setupFee().signum() > 0) {
            params.addLineItem(lineItem(SETUP_FEE_LINE_NAME, "Bestellung " + order.getOrderNumber(),
                    1L, rappen(order.getTotals().setupFee()), currency));
        }
        if (order.getTotals().shipping().signum() > 0) {
            params.addLineItem(lineItem(SHIPPING_LINE_NAME, "Bestellung " + order.getOrderNumber(),
                    1L, rappen(order.getTotals().shipping()), currency));
        }
        return params.build();
    }

    /**
     * One Stripe line per item: the unit price in whole rappen (it is cash-rounded to 0.05, so
     * the line total is always an exact multiple) and the quantity, which sums to the line total.
     */
    private static SessionCreateParams.LineItem lineItem(OrderItem item, String currency) {
        String configuration = item.getQuantity() + " x " + item.getFilament() + " " + item.getColorLabel()
                + ", " + item.getQualityLabel();
        return lineItem(item.getOriginalFileName(), configuration, item.getQuantity(),
                rappen(item.getUnitPrice()), currency);
    }

    private static SessionCreateParams.LineItem lineItem(String name, String description, long quantity,
                                                         long unitAmount, String currency) {
        SessionCreateParams.LineItem.PriceData.ProductData product = SessionCreateParams.LineItem.PriceData.ProductData.builder()
                .setName(name)
                .setDescription(description)
                .build();
        return SessionCreateParams.LineItem.builder()
                .setQuantity(quantity)
                .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                        .setCurrency(currency)
                        .setUnitAmount(unitAmount)
                        .setProductData(product)
                        .build())
                .build();
    }

    private static long rappen(BigDecimal chf) {
        return chf.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
