package com.slicercore.payment;

import com.slicercore.order.Order;
import com.slicercore.order.OrderPaidEvent;
import com.slicercore.order.OrderRepository;
import com.slicercore.order.OrderRetentionService;
import com.slicercore.order.OrderStatus;
import com.slicercore.support.MutableClock;
import com.slicercore.support.TestOrders;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    private final OrderRepository orders = mock(OrderRepository.class);
    private final MutableClock clock = MutableClock.at("2026-10-19T12:00:00Z");
    private final OrderRetentionService retention = mock(OrderRetentionService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PaymentService service = new PaymentService(orders, retention, clock, events);

    @Test
    void abandonCancelsAndErasesAnOrderThatWillNotBePaid() {
        Order order = TestOrders.benchyOrder();
        order.attachPayment("stripe", "cs_1", "https://checkout.stripe.com/c/pay/cs_1");
        when(orders.findByPaymentSessionId("cs_1")).thenReturn(Optional.of(order));

        service.abandon("cs_1");

        verify(retention).abandon(order);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void abandonLeavesAnOrderAloneThatWasPaidAfterAll() {
        Order order = TestOrders.benchyOrder();
        order.attachPayment("stripe", "cs_2", "https://checkout.stripe.com/c/pay/cs_2");
        order.markPaid(clock.instant());
        when(orders.findByPaymentSessionId("cs_2")).thenReturn(Optional.of(order));

        assertThat(service.abandon("cs_2").getStatus()).isEqualTo(OrderStatus.PAID);

        verify(retention, never()).abandon(any());
    }

    @Test
    void abandonRefusesUnknownSessions() {
        when(orders.findByPaymentSessionId(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.abandon("cs_forged")).isInstanceOf(IllegalArgumentException.class);
        verify(retention, never()).abandon(any());
    }

    @Test
    void confirmMarksTheOrderPaidOnceAndTolerantlyAgain() {
        Order order = TestOrders.benchyOrder();
        order.attachPayment("mock", "mock_1", "http://localhost:4200/pay/mock?session=mock_1");
        when(orders.findByPaymentSessionId("mock_1")).thenReturn(Optional.of(order));

        assertThat(service.confirm("mock_1").getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isEqualTo(clock.instant());

        clock.advance(Duration.ofMinutes(5));
        assertThat(service.confirm("mock_1").getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isEqualTo(Instant.parse("2026-10-19T12:00:00Z"));

        // One confirmation mail per payment, not per webhook delivery.
        verify(events, times(1)).publishEvent(new OrderPaidEvent(order));
    }

    @Test
    void unknownSessionsCannotCreateState() {
        when(orders.findByPaymentSessionId(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm("mock_forged"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown payment session");
    }
}
