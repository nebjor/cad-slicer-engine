package com.slicercore.order;

import com.slicercore.config.RateLimitProperties;
import com.slicercore.config.SecurityConfig;
import com.slicercore.model.BoundingBox;
import com.slicercore.model.ColorOption;
import com.slicercore.model.QuoteResponse;
import com.slicercore.model.RateLimitExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@Import({OrderControllerTest.Config.class, OrderErrorHandling.class, SecurityConfig.class})
class OrderControllerTest {

    @TestConfiguration
    static class Config {
        @Bean
        RateLimitProperties rateLimitProperties() {
            return new RateLimitProperties(true, 10, 100, false, "help@example.com");
        }
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    OrderService orderService;

    private final MockMultipartFile benchy = new MockMultipartFile(
            "file", "benchy.stl", "application/octet-stream", "solid benchy endsolid benchy".getBytes());

    @Test
    void placingAnOrderReturns201WithLocationAndBody() throws Exception {
        when(orderService.placeOrder(any(), any(), anyString())).thenReturn(sampleOrder());

        mockMvc.perform(checkout().param("quantity", "2").param("expectedTotalChf", "12.10"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/orders/POD-1000"))
                .andExpect(jsonPath("$.orderNumber").value("POD-1000"))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.totals.total").value(21.10))
                .andExpect(jsonPath("$.vatNote").value(containsString("not VAT registered")))
                .andExpect(jsonPath("$.paymentUrl").value(containsString("/pay/mock?session=")));

        verify(orderService).placeOrder(any(), any(), eq("127.0.0.1"));
    }

    @Test
    void missingAndMalformedFieldsAre400WithAnErrorMap() throws Exception {
        mockMvc.perform(multipart("/api/orders").file(benchy)
                        .param("filament", "PLA").param("quality", "medium").param("quantity", "0")
                        .param("customerName", "").param("email", "not-an-email")
                        .param("street", "Bahnhofstrasse 1").param("postalCode", "8001").param("city", "Zürich")
                        .param("country", "Switzerland"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.quantity").exists())
                .andExpect(jsonPath("$.errors.customerName").exists())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.country").value(containsString("two-letter")));

        verifyNoInteractions(orderService);
    }

    @Test
    void changedPriceIs409WithTheCurrentQuote() throws Exception {
        when(orderService.placeOrder(any(), any(), anyString())).thenThrow(new PriceChangedException(sampleQuote()));

        mockMvc.perform(checkout().param("quantity", "2").param("expectedTotalChf", "11.00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentQuote.totalPriceChf").value(12.1));
    }

    @Test
    void quoteFailuresKeepTheirStatusCodes() throws Exception {
        when(orderService.placeOrder(any(), any(), anyString())).thenThrow(new RateLimitExceededException("Rate limit reached.", 42));

        mockMvc.perform(checkout().param("quantity", "1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.supportEmail").value("help@example.com"));
    }

    @Test
    void unsupportedCountryIs400() throws Exception {
        when(orderService.placeOrder(any(), any(), anyString()))
                .thenThrow(new IllegalArgumentException("Shipping to DE is not offered. Available: CH"));

        mockMvc.perform(checkout("DE").param("quantity", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("DE")));
    }

    @Test
    void settingsTellTheCheckoutPageWhatToShow() throws Exception {
        when(orderService.settings()).thenReturn(new OrderSettingsResponse("CHF", new BigDecimal("9.00"), false,
                new BigDecimal("0.081"), 14, java.util.List.of("CH")));

        mockMvc.perform(get("/api/orders/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shippingFlatChf").value(9.0))
                .andExpect(jsonPath("$.vatEnabled").value(false))
                .andExpect(jsonPath("$.shipToCountries[0]").value("CH"));
    }

    @Test
    void lookupNeedsTheEmailAndAnswers404Otherwise() throws Exception {
        when(orderService.find("POD-1000", "anna@example.com")).thenReturn(Optional.of(sampleOrder()));
        when(orderService.find("POD-1000", "other@example.com")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/orders/POD-1000").param("email", "anna@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("POD-1000"));
        mockMvc.perform(get("/api/orders/POD-1000").param("email", "other@example.com"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/orders/POD-1000"))
                .andExpect(status().isBadRequest());
    }

    private MockMultipartHttpServletRequestBuilder checkout() {
        return checkout("CH");
    }

    private MockMultipartHttpServletRequestBuilder checkout(String country) {
        return (MockMultipartHttpServletRequestBuilder) multipart("/api/orders").file(benchy)
                .param("filament", "PLA").param("color", "red").param("quality", "medium")
                .param("customerName", "Anna Muster").param("email", "anna@example.com")
                .param("street", "Bahnhofstrasse 1").param("postalCode", "8001").param("city", "Zürich")
                .param("country", country);
    }

    private static QuoteResponse sampleQuote() {
        return new QuoteResponse("benchy.stl", "PLA", new ColorOption("red", "Red", "#d62828"), "medium", 2,
                "Bambu Lab A1 0.4 nozzle", 0.2, 1, 2239, 4478, 7761.2, 11.6, 23.2, new BoundingBox(60, 31, 48),
                225154, 5.0, 0.8, 5.0, 3.55, 12.1, "CHF", 3);
    }

    private static OrderResponse sampleOrder() {
        OrderTotals totals = new OrderTotals(new BigDecimal("5.00"), new BigDecimal("3.55"), new BigDecimal("12.10"),
                new BigDecimal("9.00"), new BigDecimal("0.0000"), new BigDecimal("0.00"), new BigDecimal("21.10"), "CHF");
        return new OrderResponse("POD-1000", OrderStatus.NEW, Instant.parse("2026-10-19T09:00:00Z"),
                Instant.parse("2026-11-02T09:00:00Z"), null, "Anna Muster", "anna@example.com", "benchy.stl", "PLA",
                "red", "Red", "medium", 2, 1, 4478, new BigDecimal("23.2"), totals,
                "No VAT charged: the seller is not VAT registered.",
                "http://localhost:4200/pay/mock?session=mock_1&order=POD-1000&amount=21.10&currency=CHF");
    }
}
