package com.slicercore.payment;

import com.slicercore.config.RateLimitProperties;
import com.slicercore.config.SecurityConfig;
import com.slicercore.order.Order;
import com.slicercore.order.OrderStatus;
import com.slicercore.support.TestOrders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

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
        Order order = TestOrders.benchyOrder();
        order.transitionTo(OrderStatus.PAID, Instant.parse("2026-10-19T11:05:00Z"));
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
}
