package com.slicercore.payment;

import com.slicercore.order.Order;
import com.slicercore.order.PaymentSession;
import com.slicercore.support.TestOrders;
import com.stripe.exception.ApiConnectionException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static com.slicercore.support.TestOrders.ANNA;
import static com.slicercore.support.TestOrders.RED;
import static com.slicercore.support.TestOrders.SETTINGS;
import static com.slicercore.support.TestOrders.quote;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripeCheckoutGatewayTest {

    private final PaymentProperties properties = new PaymentProperties("stripe", "https://3dburg.ch");
    private final StripeCheckoutGateway gateway = new StripeCheckoutGateway(properties, params -> new Session());

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

        PaymentSession started = gateway.start(TestOrders.benchyOrder());

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
    void chargesOneLineItemPerOrderItemThenTheSetupFeeOnceThenTheFlatShipping() {
        Order order = TestOrders.twoItemOrder();
        assertThat(order.getTotals().total()).isEqualByComparingTo("23.00");

        List<SessionCreateParams.LineItem> items = gateway.checkoutParams(order).getLineItems();

        assertThat(items).hasSize(4);
        assertThat(items).allSatisfy(item -> assertThat(item.getPriceData().getCurrency()).isEqualTo("chf"));
        // 6.00 for two copies: Stripe shows 2 x 3.00.
        assertThat(items.get(0).getPriceData().getProductData().getName()).isEqualTo("benchy.stl");
        assertThat(items.get(0).getPriceData().getProductData().getDescription()).isEqualTo("2 x PLA Red, Standard");
        assertThat(items.get(0).getQuantity()).isEqualTo(2L);
        assertThat(items.get(0).getPriceData().getUnitAmount()).isEqualTo(300L);
        assertThat(items.get(1).getPriceData().getProductData().getName()).isEqualTo("halter.3mf");
        assertThat(items.get(1).getPriceData().getProductData().getDescription()).isEqualTo("1 x PETG Black, Fein");
        assertThat(items.get(1).getQuantity()).isEqualTo(1L);
        assertThat(items.get(1).getPriceData().getUnitAmount()).isEqualTo(300L);
        assertThat(items.get(2).getPriceData().getProductData().getName()).isEqualTo("Einrichtung");
        assertThat(items.get(2).getQuantity()).isEqualTo(1L);
        assertThat(items.get(2).getPriceData().getUnitAmount()).isEqualTo(500L);
        assertThat(items.get(3).getPriceData().getProductData().getName()).isEqualTo("Versand (Pauschale)");
        assertThat(items.get(3).getQuantity()).isEqualTo(1L);
        assertThat(items.get(3).getPriceData().getUnitAmount()).isEqualTo(900L);
        assertThat(sumInRappen(items)).isEqualTo(2300L);
    }

    @Test
    void theCheckoutTotalEqualsTheOrderTotalWhateverTheQuantity() {
        // 3 x 3.55 = 10.65 plus the 5.00 setup fee plus 9.00 shipping.
        Order order = TestOrders.order("POD-1001", SETTINGS, ANNA, quote("deckel.stl", RED, 3, 3.55));
        assertThat(order.getTotals().total()).isEqualByComparingTo("24.65");

        List<SessionCreateParams.LineItem> items = gateway.checkoutParams(order).getLineItems();

        assertThat(items.get(0).getQuantity()).isEqualTo(3L);
        assertThat(items.get(0).getPriceData().getUnitAmount()).isEqualTo(355L);
        assertThat(sumInRappen(items)).isEqualTo(2465L);
    }

    @Test
    void freeShippingAddsNoLineItem() {
        Order order = TestOrders.order("POD-1002", new com.slicercore.order.OrderProperties(BigDecimal.ZERO, new BigDecimal("5.00"), new BigDecimal("3.00"), false,
                new BigDecimal("0.081"), 14, 90, "unused", Set.of("CH")), ANNA, quote("benchy.stl", RED, 1, 3.0));

        List<SessionCreateParams.LineItem> items = gateway.checkoutParams(order).getLineItems();

        assertThat(items).hasSize(2);
        assertThat(items.get(1).getPriceData().getProductData().getName()).isEqualTo("Einrichtung");
        assertThat(sumInRappen(items)).isEqualTo(800L);
    }

    @Test
    void aStripeOutageSurfacesAsAFailureToStartThePayment() {
        StripeCheckoutGateway gateway = new StripeCheckoutGateway(properties, params -> {
            throw new ApiConnectionException("connection refused");
        });

        assertThatThrownBy(() -> gateway.start(TestOrders.benchyOrder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POD-1000")
                .hasCauseInstanceOf(ApiConnectionException.class);
    }

    private static long sumInRappen(List<SessionCreateParams.LineItem> items) {
        return items.stream().mapToLong(item -> item.getQuantity() * item.getPriceData().getUnitAmount()).sum();
    }
}
