package com.slicercore.payment;

import com.slicercore.order.Order;
import com.slicercore.order.PaymentSession;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockPaymentGatewayTest {

    @Test
    void startsAShopRelativeSessionCarryingOrderAndAmount() {
        MockPaymentGateway gateway = new MockPaymentGateway(new PaymentProperties("mock", "https://shop.example"));
        Order order = PaymentServiceTest.order();

        PaymentSession session = gateway.start(order);

        assertThat(session.provider()).isEqualTo("mock");
        assertThat(session.sessionId()).startsWith("mock_");
        assertThat(session.url()).startsWith("https://shop.example/pay/mock?session=mock_")
                .contains("order=POD-1000").contains("amount=20.00").contains("currency=CHF");
    }

    @Test
    void everySessionIdIsUnique() {
        MockPaymentGateway gateway = new MockPaymentGateway(new PaymentProperties("mock", "http://localhost:4200"));
        Order order = PaymentServiceTest.order();

        assertThat(gateway.start(order).sessionId()).isNotEqualTo(gateway.start(order).sessionId());
    }
}
