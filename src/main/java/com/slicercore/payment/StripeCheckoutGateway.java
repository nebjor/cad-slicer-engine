package com.slicercore.payment;

import com.slicercore.order.Order;
import com.slicercore.order.PaymentGateway;
import com.slicercore.order.PaymentSession;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Starts a Stripe Checkout session for an order: one line item for the whole order, the
 * amount in rappen, the order number as reference, and the shop's success page as both return
 * targets. The customer pays on Stripe's page; the session becomes a paid order only through
 * {@link StripeWebhookController}.
 */
@Component
@ConditionalOnProperty(name = "payments.provider", havingValue = PaymentProperties.STRIPE)
public class StripeCheckoutGateway implements PaymentGateway {

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
        long amountInRappen = order.getTotals().total()
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();

        SessionCreateParams.LineItem.PriceData.ProductData product = SessionCreateParams.LineItem.PriceData.ProductData.builder()
                .setName("3DBurg Bestellung " + order.getOrderNumber())
                .setDescription(order.getQuantity() + " x " + order.getOriginalFileName() + ", "
                        + order.getFilament() + " " + order.getColorLabel() + ", inkl. Versand")
                .build();
        SessionCreateParams.LineItem lineItem = SessionCreateParams.LineItem.builder()
                .setQuantity(1L)
                .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                        .setCurrency(order.getTotals().currency().toLowerCase())
                        .setUnitAmount(amountInRappen)
                        .setProductData(product)
                        .build())
                .build();

        return SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setLocale(SessionCreateParams.Locale.DE)
                .setClientReferenceId(order.getOrderNumber())
                .putMetadata("orderNumber", order.getOrderNumber())
                .setCustomerEmail(order.getCustomerEmail())
                .addLineItem(lineItem)
                .setSuccessUrl(successPage)
                .setCancelUrl(successPage + "&cancelled=1")
                .build();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
