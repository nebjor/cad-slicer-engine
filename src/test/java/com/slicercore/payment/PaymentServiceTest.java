package com.slicercore.payment;

import com.slicercore.model.BoundingBox;
import com.slicercore.model.ColorOption;
import com.slicercore.model.QuoteResponse;
import com.slicercore.order.Order;
import com.slicercore.order.OrderFileStorage;
import com.slicercore.order.OrderPaidEvent;
import com.slicercore.order.OrderProperties;
import com.slicercore.order.OrderRepository;
import com.slicercore.order.OrderStatus;
import com.slicercore.order.OrderTotals;
import com.slicercore.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    private final OrderRepository orders = mock(OrderRepository.class);
    private final MutableClock clock = MutableClock.at("2026-10-19T12:00:00Z");
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PaymentService service = new PaymentService(orders, clock, events);

    @Test
    void confirmMarksTheOrderPaidOnceAndTolerantlyAgain() {
        Order order = order();
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

    static Order order() {
        QuoteResponse quote = new QuoteResponse("benchy.stl", "PLA", new ColorOption("red", "Red", "#d62828"),
                "medium", 2, "A1", 0.2, 1, 2239, 4478, 7761.2, 11.6, 23.2, new BoundingBox(60, 31, 48),
                225154, 5.0, 0.8, 5.0, 3.0, 11.0, "CHF", 3);
        OrderProperties settings = new OrderProperties(new BigDecimal("9.00"), false, new BigDecimal("0.081"),
                14, 90, "unused", Set.of("CH"));
        return Order.create("POD-1000", Instant.parse("2026-10-19T11:00:00Z"), 14,
                new Order.Customer("Anna", "anna@example.com", "S 1", "8000", "Z", "CH"), quote,
                new OrderFileStorage.StoredFile("123e4567-e89b-12d3-a456-426614174000.stl", "a".repeat(64), 1),
                OrderTotals.from(quote, settings));
    }
}
