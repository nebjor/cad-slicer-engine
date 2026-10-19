package com.slicercore.payment;

import com.slicercore.config.RateLimitProperties;
import com.slicercore.config.SecurityConfig;
import com.slicercore.model.BoundingBox;
import com.slicercore.model.ColorOption;
import com.slicercore.model.QuoteResponse;
import com.slicercore.order.Order;
import com.slicercore.order.OrderFileStorage;
import com.slicercore.order.OrderProperties;
import com.slicercore.order.OrderStatus;
import com.slicercore.order.OrderTotals;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MockPaymentController.class)
@Import({MockPaymentControllerTest.Config.class, SecurityConfig.class})
class MockPaymentControllerTest {

    /** The order advice is part of every web slice and needs the rate-limit settings. */
    @TestConfiguration
    static class Config {
        @Bean
        RateLimitProperties rateLimitProperties() {
            return new RateLimitProperties(true, 10, 100, false, "");
        }
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PaymentService paymentService;

    @Test
    void completingASessionReportsThePaidOrder() throws Exception {
        Order order = paidOrder();
        when(paymentService.confirm("mock_abc")).thenReturn(order);

        mockMvc.perform(post("/api/payments/mock/mock_abc/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("POD-1000"))
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void unknownSessionIs404() throws Exception {
        when(paymentService.confirm("mock_nope")).thenThrow(new IllegalArgumentException("Unknown payment session."));

        mockMvc.perform(post("/api/payments/mock/mock_nope/complete"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Unknown payment session."));
    }

    private static Order paidOrder() {
        QuoteResponse quote = new QuoteResponse("benchy.stl", "PLA", new ColorOption("red", "Red", "#d62828"),
                "medium", 1, "A1", 0.2, 1, 2239, 2239, 3880.6, 11.6, 11.6, new BoundingBox(60, 31, 48),
                225154, 2.5, 0.4, 5.0, 3.0, 8.0, "CHF", 3);
        OrderProperties settings = new OrderProperties(new BigDecimal("9.00"), false, new BigDecimal("0.081"),
                14, 90, "unused", Set.of("CH"));
        Order order = Order.create("POD-1000", Instant.parse("2026-10-19T11:00:00Z"), 14,
                new Order.Customer("Anna", "anna@example.com", "S 1", "8000", "Z", "CH"), quote,
                new OrderFileStorage.StoredFile("123e4567-e89b-12d3-a456-426614174000.stl", "a".repeat(64), 1),
                OrderTotals.from(quote, settings));
        order.transitionTo(OrderStatus.PAID, Instant.parse("2026-10-19T11:05:00Z"));
        return order;
    }
}
