package com.slicercore.admin;

import com.slicercore.config.RateLimitProperties;
import com.slicercore.config.SecurityConfig;
import com.slicercore.order.Order;
import com.slicercore.order.OrderRetentionService;
import com.slicercore.order.OrderStatus;
import com.slicercore.service.QuoteRateLimiter;
import com.slicercore.support.TestOrders;
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
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
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
        mockMvc.perform(get("/api/admin/orders/POD-1000/items/1/model"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(orders);
    }

    @Test
    void ownerCanListAndFilterOrdersWithTheirItems() throws Exception {
        when(orders.list(eq(Optional.empty()), anyInt())).thenReturn(List.of(sample(OrderStatus.PAID)));
        when(orders.list(eq(Optional.of(OrderStatus.NEW)), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/orders").with(httpBasic("admin", "secret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderNumber").value("POD-1000"))
                .andExpect(jsonPath("$[0].allowedTransitions[0]").value("IN_REVIEW"))
                .andExpect(jsonPath("$[0].street").value("Bahnhofstrasse 1"))
                .andExpect(jsonPath("$[0].items.length()").value(2))
                .andExpect(jsonPath("$[0].items[1].position").value(2))
                .andExpect(jsonPath("$[0].items[1].fileName").value("halter.3mf"))
                .andExpect(jsonPath("$[0].items[1].fileSizeBytes").value(2000))
                .andExpect(jsonPath("$[0].items[1].machineProfile").value("Bambu Lab A1 0.4 nozzle"))
                .andExpect(jsonPath("$[0].totals.total").value(28.00));
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
    void modelDownloadAddressesAnItemAndCarriesTheOriginalNameBehindTheOrderNumber() throws Exception {
        byte[] bytes = "solid halter endsolid halter".getBytes();
        when(orders.model("POD-1000", 2)).thenReturn(
                new AdminOrderService.ModelDownload("POD-1000_2_halter.3mf", bytes.length, new ByteArrayInputStream(bytes)));
        when(orders.model("POD-1000", 9)).thenThrow(new NoSuchElementException("Order POD-1000 has no item 9."));

        mockMvc.perform(get("/api/admin/orders/POD-1000/items/2/model").with(httpBasic("admin", "secret")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("POD-1000_2_halter.3mf")))
                .andExpect(header().string("Content-Length", String.valueOf(bytes.length)))
                .andExpect(content().bytes(bytes));
        mockMvc.perform(get("/api/admin/orders/POD-1000/items/9/model").with(httpBasic("admin", "secret")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString("no item 9")));
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
        Order order = TestOrders.twoItemOrder();
        order.attachPayment("mock", "mock_1", "http://localhost:4200/pay/mock?session=mock_1");
        order.transitionTo(OrderStatus.PAID, Instant.parse("2026-10-19T09:05:00Z"));
        if (status != OrderStatus.PAID) {
            order.transitionTo(status, Instant.parse("2026-10-19T09:10:00Z"));
        }
        return AdminOrderResponse.from(order);
    }
}
