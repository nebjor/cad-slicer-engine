package com.slicercore.payment;

import com.slicercore.order.Order;
import com.slicercore.order.PaymentSession;
import com.slicercore.support.TestOrders;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockPaymentGatewayTest {

    @Test
    void startsAShopRelativeSessionCarryingOrderAndAmount() {
        MockPaymentGateway gateway = new MockPaymentGateway(new PaymentProperties("mock", "https://shop.example"));
        Order order = TestOrders.twoItemOrder();

        PaymentSession session = gateway.start(order);

        assertThat(session.provider()).isEqualTo("mock");
        assertThat(session.sessionId()).startsWith("mock_");
        assertThat(session.url()).startsWith("https://shop.example/pay/mock?session=mock_")
                .contains("order=POD-1000").contains("amount=28.00").contains("currency=CHF");
    }

    @Test
    void everySessionIdIsUnique() {
        MockPaymentGateway gateway = new MockPaymentGateway(new PaymentProperties("mock", "http://localhost:4200"));
        Order order = TestOrders.benchyOrder();

        assertThat(gateway.start(order).sessionId()).isNotEqualTo(gateway.start(order).sessionId());
    }
}
