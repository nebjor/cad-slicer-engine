package com.slicercore.payment;

import com.slicercore.model.BoundingBox;
import com.slicercore.model.ColorOption;
import com.slicercore.model.QuoteResponse;
import com.slicercore.order.Order;
import com.slicercore.order.OrderFileStorage;
import com.slicercore.order.OrderProperties;
import com.slicercore.order.OrderRepository;
import com.slicercore.order.OrderStatus;
import com.slicercore.order.OrderTotals;
import com.slicercore.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    private final OrderRepository orders = mock(OrderRepository.class);
    private final MutableClock clock = MutableClock.at("2026-10-19T12:00:00Z");
    private final PaymentProperties properties = new PaymentProperties("mock", "https://shop.example");
    private final PaymentService service = new PaymentService(new MockPaymentGateway(properties), orders, clock);

    @Test
    void startAttachesAShopRelativeMockPageToTheOrder() {
        Order order = order();

        PaymentSession session = service.start(order);

        assertThat(session.provider()).isEqualTo("mock");
        assertThat(session.sessionId()).startsWith("mock_");
        assertThat(session.url()).startsWith("https://shop.example/pay/mock?session=mock_")
                .contains("order=POD-1000").contains("amount=20.00").contains("currency=CHF");
        assertThat(order.getPaymentSessionId()).isEqualTo(session.sessionId());
        assertThat(order.getPaymentUrl()).isEqualTo(session.url());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);
    }

    @Test
    void confirmMarksTheOrderPaidOnceAndTolerantlyAgain() {
        Order order = order();
        PaymentSession session = service.start(order);
        when(orders.findByPaymentSessionId(session.sessionId())).thenReturn(Optional.of(order));

        assertThat(service.confirm(session.sessionId()).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isEqualTo(clock.instant());

        clock.advance(java.time.Duration.ofMinutes(5));
        assertThat(service.confirm(session.sessionId()).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isEqualTo(Instant.parse("2026-10-19T12:00:00Z"));
    }

    @Test
    void unknownSessionsCannotCreateState() {
        when(orders.findByPaymentSessionId(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm("mock_forged"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown payment session");
    }

    private static Order order() {
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
