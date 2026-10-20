package com.slicercore.admin;

import com.slicercore.config.RateLimitProperties;
import com.slicercore.config.SecurityConfig;
import com.slicercore.order.OrderRetentionService;
import com.slicercore.order.OrderStatus;
import com.slicercore.order.OrderTotals;
import com.slicercore.service.QuoteRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The admin API is reachable with the owner's credentials only; the rest of the contract on top. */
@WebMvcTest(value = AdminOrderController.class, properties = {"admin.username=admin", "admin.password=secret"})
@Import({SecurityConfig.class, AdminOrderControllerTest.Config.class})
class AdminOrderControllerTest {

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
    AdminOrderService orders;

    @MockitoBean
    QuoteRateLimiter rateLimiter;

    @MockitoBean
    OrderRetentionService retention;

    @Test
    void withoutCredentialsEverythingIs401WithoutABrowserChallenge() throws Exception {
        mockMvc.perform(get("/api/admin/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(jsonPath("$.detail").value("Admin credentials required."));
        mockMvc.perform(get("/api/admin/orders").with(httpBasic("admin", "wrong")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(orders);
    }

    @Test
    void ownerCanListAndFilterOrders() throws Exception {
        when(orders.list(eq(Optional.empty()), anyInt())).thenReturn(List.of(sample(OrderStatus.PAID)));
        when(orders.list(eq(Optional.of(OrderStatus.NEW)), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/orders").with(httpBasic("admin", "secret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderNumber").value("POD-1000"))
                .andExpect(jsonPath("$[0].allowedTransitions[0]").value("IN_REVIEW"))
                .andExpect(jsonPath("$[0].street").value("Bahnhofstrasse 1"));
        mockMvc.perform(get("/api/admin/orders").param("status", "NEW").with(httpBasic("admin", "secret")))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void statusChangeFollowsTheStateMachine() throws Exception {
        when(orders.transition("POD-1000", OrderStatus.IN_REVIEW)).thenReturn(sample(OrderStatus.IN_REVIEW));
        when(orders.transition("POD-1000", OrderStatus.SHIPPED))
                .thenThrow(new IllegalStateException("Order POD-1000 cannot go from PAID to SHIPPED."));

        mockMvc.perform(post("/api/admin/orders/POD-1000/status").with(httpBasic("admin", "secret"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_REVIEW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_REVIEW"));
        mockMvc.perform(post("/api/admin/orders/POD-1000/status").with(httpBasic("admin", "secret"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SHIPPED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("cannot go from PAID to SHIPPED")));
        mockMvc.perform(post("/api/admin/orders/POD-1000/status").with(httpBasic("admin", "secret"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownOrderIs404() throws Exception {
        when(orders.get("POD-9")).thenThrow(new NoSuchElementException("Order POD-9 not found."));

        mockMvc.perform(get("/api/admin/orders/POD-9").with(httpBasic("admin", "secret")))
                .andExpect(status().isNotFound());
    }

    @Test
    void modelDownloadCarriesTheOriginalNameBehindTheOrderNumber() throws Exception {
        byte[] bytes = "solid benchy endsolid benchy".getBytes();
        when(orders.model("POD-1000")).thenReturn(
                new AdminOrderService.ModelDownload("POD-1000_benchy.stl", bytes.length, new ByteArrayInputStream(bytes)));

        mockMvc.perform(get("/api/admin/orders/POD-1000/model").with(httpBasic("admin", "secret")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("POD-1000_benchy.stl")))
                .andExpect(header().string("Content-Length", String.valueOf(bytes.length)))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void rateLimitResetMovedBehindTheLogin() throws Exception {
        when(rateLimiter.reset("203.0.113.7")).thenReturn(true);

        mockMvc.perform(post("/api/admin/rate-limits/reset").param("clientKey", "203.0.113.7"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/rate-limits/reset").param("clientKey", "203.0.113.7")
                        .with(httpBasic("admin", "secret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hadUsage").value(true));
        mockMvc.perform(post("/api/admin/rate-limits/reset").with(httpBasic("admin", "secret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reset").value("all"));
        verify(rateLimiter).resetAll();
    }

    private static AdminOrderResponse sample(OrderStatus status) {
        OrderTotals totals = new OrderTotals(new BigDecimal("5.00"), new BigDecimal("3.00"), new BigDecimal("11.00"),
                new BigDecimal("9.00"), new BigDecimal("0.0000"), new BigDecimal("0.00"), new BigDecimal("20.00"), "CHF");
        return new AdminOrderResponse("POD-1000", status, status.allowedTransitions().stream().sorted().toList(),
                Instant.parse("2026-10-19T09:00:00Z"), Instant.parse("2026-11-02T09:00:00Z"),
                Instant.parse("2026-10-19T09:05:00Z"), null, null, null,
                "Anna Muster", "anna@example.com", "Bahnhofstrasse 1", "8001", "Zürich", "CH",
                "benchy.stl", 11_257_784L, "PLA", "red", "Red", "medium", 2, "Bambu Lab A1 0.4 nozzle",
                new BigDecimal("0.20"), 1, 3903, new BigDecimal("23.1"), totals, "mock");
    }
}
