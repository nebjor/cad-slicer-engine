package com.slicercore.payment;

import com.slicercore.model.BoundingBox;
import com.slicercore.model.ColorOption;
import com.slicercore.model.QuoteResponse;
import com.slicercore.order.Order;
import com.slicercore.order.OrderFileStorage;
import com.slicercore.order.OrderProperties;
import com.slicercore.order.OrderTotals;
import com.slicercore.order.PaymentSession;
import com.stripe.exception.ApiConnectionException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripeCheckoutGatewayTest {

    private final PaymentProperties properties = new PaymentProperties("stripe", "https://3dburg.ch");

    @Test
    void startsACheckoutSessionAndHandsBackItsPage() {
        AtomicReference<SessionCreateParams> sent = new AtomicReference<>();
        StripeCheckoutGateway gateway = new StripeCheckoutGateway(properties, params -> {
            sent.set(params);
            Session session = new Session();
            session.setId("cs_test_123");
            session.setUrl("https://checkout.stripe.com/c/pay/cs_test_123");
            return session;
        });

        PaymentSession started = gateway.start(order());

        assertThat(started).isEqualTo(new PaymentSession("stripe", "cs_test_123",
                "https://checkout.stripe.com/c/pay/cs_test_123"));
        SessionCreateParams params = sent.get();
        assertThat(params.getMode()).isEqualTo(SessionCreateParams.Mode.PAYMENT);
        assertThat(params.getClientReferenceId()).isEqualTo("POD-1000");
        assertThat(params.getMetadata()).containsEntry("orderNumber", "POD-1000");
        assertThat(params.getCustomerEmail()).isEqualTo("anna@example.com");
        assertThat(params.getSuccessUrl()).isEqualTo("https://3dburg.ch/checkout/success?order=POD-1000");
        assertThat(params.getCancelUrl()).isEqualTo("https://3dburg.ch/checkout/success?order=POD-1000&cancelled=1");
    }

    @Test
    void chargesTheOrderTotalInRappenAsOneLineItem() {
        StripeCheckoutGateway gateway = new StripeCheckoutGateway(properties, params -> new Session());
        Order order = order();
        assertThat(order.getTotals().total()).isEqualByComparingTo("17.00");

        SessionCreateParams params = gateway.checkoutParams(order);

        assertThat(params.getLineItems()).hasSize(1);
        SessionCreateParams.LineItem item = params.getLineItems().get(0);
        assertThat(item.getQuantity()).isEqualTo(1L);
        assertThat(item.getPriceData().getCurrency()).isEqualTo("chf");
        assertThat(item.getPriceData().getUnitAmount()).isEqualTo(1700L);
        assertThat(item.getPriceData().getProductData().getName()).isEqualTo("3DBurg Bestellung POD-1000");
        assertThat(item.getPriceData().getProductData().getDescription())
                .isEqualTo("1 x benchy.stl, PLA Red, inkl. Versand");
    }

    @Test
    void aStripeOutageSurfacesAsAFailureToStartThePayment() {
        StripeCheckoutGateway gateway = new StripeCheckoutGateway(properties, params -> {
            throw new ApiConnectionException("connection refused");
        });

        assertThatThrownBy(() -> gateway.start(order()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POD-1000")
                .hasCauseInstanceOf(ApiConnectionException.class);
    }

    private static Order order() {
        QuoteResponse quote = new QuoteResponse("benchy.stl", "PLA", new ColorOption("red", "Red", "#d62828", null),
                "medium", 1, "A1", 0.2, 1, 2239, 2239, 3880.6, 11.6, 11.6, new BoundingBox(60, 31, 48),
                225154, 2.5, 0.4, 5.0, 3.0, 8.0, "CHF", 3);
        OrderProperties settings = new OrderProperties(new BigDecimal("9.00"), false, new BigDecimal("0.081"),
                14, 90, "unused", Set.of("CH"));
        return Order.create("POD-1000", Instant.parse("2026-10-19T11:00:00Z"), 14,
                new Order.Customer("Anna", "anna@example.com", "S 1", "8000", "Z", "CH"), quote,
                new OrderFileStorage.StoredFile("123e4567-e89b-12d3-a456-426614174000.stl", "a".repeat(64), 1),
                OrderTotals.from(quote, settings));
    }
}
