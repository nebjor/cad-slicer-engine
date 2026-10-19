package com.slicercore.payment;

import com.slicercore.order.Order;
import com.slicercore.order.PaymentGateway;
import com.slicercore.order.PaymentSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Stand-in for a real provider while the Stripe account is pending. It mirrors the real
 * flow, redirect out and confirmation back, so swapping the provider is configuration only.
 * The payment page is a route of the shop itself that calls
 * {@code POST /api/payments/mock/{sessionId}/complete}.
 */
@Component
@ConditionalOnProperty(name = "payments.provider", havingValue = PaymentProperties.MOCK, matchIfMissing = true)
public class MockPaymentGateway implements PaymentGateway {

    private final PaymentProperties properties;

    public MockPaymentGateway(PaymentProperties properties) {
        this.properties = properties;
    }

    @Override
    public PaymentSession start(Order order) {
        String sessionId = "mock_" + UUID.randomUUID();
        String url = properties.publicBaseUrl() + "/pay/mock"
                + "?session=" + encode(sessionId)
                + "&order=" + encode(order.getOrderNumber())
                + "&amount=" + encode(order.getTotals().total().toPlainString())
                + "&currency=" + encode(order.getTotals().currency());
        return new PaymentSession(PaymentProperties.MOCK, sessionId, url);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
