package com.slicercore.order;

import com.slicercore.config.RateLimitProperties;
import com.slicercore.config.SecurityConfig;
import com.slicercore.model.QuoteResponse;
import com.slicercore.model.RateLimitExceededException;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import jakarta.servlet.http.Cookie;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    private static final String BENCHY_ID = "a".repeat(64);
    private static final String BENCHY_ITEM = item(BENCHY_ID, "benchy.stl", 2);

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    OrderService orderService;

    @MockitoBean
    CurrentAccount currentAccount;

    @Test
    void placingAnOrderReturns201WithLocationAndBody() throws Exception {
        when(orderService.placeOrder(any(), anyString(), any())).thenReturn(sampleOrder());

        mockMvc.perform(checkout("CH", "19.00", BENCHY_ITEM, item("b".repeat(64), "halter.3mf", 1)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/orders/POD-1000"))
                .andExpect(jsonPath("$.orderNumber").value("POD-1000"))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].position").value(1))
                .andExpect(jsonPath("$.items[0].fileName").value("benchy.stl"))
                .andExpect(jsonPath("$.items[0].color.code").value("red"))
                .andExpect(jsonPath("$.items[0].color.hex").value("#d62828"))
                .andExpect(jsonPath("$.items[0].lineTotalChf").value(11.00))
                .andExpect(jsonPath("$.items[1].fileName").value("halter.3mf"))
                .andExpect(jsonPath("$.totals.total").value(28.00))
                .andExpect(jsonPath("$.vatNote").value(containsString("not VAT registered")))
                .andExpect(jsonPath("$.paymentUrl").value(containsString("/pay/mock?session=")));

        verify(orderService).placeOrder(any(), eq("127.0.0.1"), isNull());
    }

    @Test
    void aLoggedInCustomersOrderCarriesTheAccountId() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(currentAccount.idFor(any())).thenReturn(Optional.of(accountId));
        when(orderService.placeOrder(any(), anyString(), eq(accountId))).thenReturn(sampleOrder());

        mockMvc.perform(checkout("CH", "19.00", BENCHY_ITEM).cookie(new Cookie("pod_session", "secret")))
                .andExpect(status().isCreated());

        verify(orderService).placeOrder(any(), eq("127.0.0.1"), eq(accountId));
    }

    @Test
    void missingAndMalformedFieldsAre400WithAnErrorMap() throws Exception {
        String body = """
                {"customer": {"name": "", "email": "not-an-email", "street": "Bahnhofstrasse 1",
                              "postalCode": "8001", "city": "Zürich", "country": "Switzerland"},
                 "items": [{"modelId": "%s", "filament": "PLA", "quality": "medium", "quantity": 0}]}
                """.formatted(BENCHY_ID);

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['customer.name']").exists())
                .andExpect(jsonPath("$.errors['customer.email']").exists())
                .andExpect(jsonPath("$.errors['customer.country']").value(containsString("two-letter")))
                .andExpect(jsonPath("$.errors['items[0].quantity']").exists());

        verifyNoInteractions(orderService);
    }

    @Test
    void anOrderNeedsBetweenOneAndTenItems() throws Exception {
        mockMvc.perform(checkout("CH", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.items").exists());
        mockMvc.perform(checkout("CH", null, Collections.nCopies(11, BENCHY_ITEM).toArray(String[]::new)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.items").exists());

        verifyNoInteractions(orderService);
    }

    @Test
    void anUnreadableBodyIs400() throws Exception {
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{\"customer\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("could not be read")));
    }

    @Test
    void anExpiredModelIs404WithTheCodeAndTheItemIndex() throws Exception {
        when(orderService.placeOrder(any(), anyString(), any())).thenThrow(new OrderModelExpiredException(1));

        mockMvc.perform(checkout("CH", "19.00", BENCHY_ITEM, item("b".repeat(64), "halter.3mf", 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("model-expired"))
                .andExpect(jsonPath("$.itemIndex").value(1))
                .andExpect(jsonPath("$.detail").value(containsString("item 2")));
    }

    @Test
    void changedPriceIs409WithTheCurrentQuotesAndTotals() throws Exception {
        Order current = TestOrders.twoItemOrder();
        List<QuoteResponse> quotes = List.of(TestOrders.quote("benchy.stl", TestOrders.RED, 2, 3.0),
                TestOrders.quote("halter.3mf", "PETG", TestOrders.BLACK, "high", 1, 3.0));
        when(orderService.placeOrder(any(), anyString(), any())).thenThrow(new PriceChangedException(quotes, current.getTotals()));

        mockMvc.perform(checkout("CH", "17.00", BENCHY_ITEM, item("b".repeat(64), "halter.3mf", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("price-changed"))
                .andExpect(jsonPath("$.detail").value(containsString("price has changed")))
                .andExpect(jsonPath("$.currentQuotes.length()").value(2))
                .andExpect(jsonPath("$.currentQuotes[0].totalPriceChf").value(11.0))
                .andExpect(jsonPath("$.currentQuotes[1].fileName").value("halter.3mf"))
                .andExpect(jsonPath("$.totals.itemsTotal").value(19.00))
                .andExpect(jsonPath("$.totals.shipping").value(9.00))
                .andExpect(jsonPath("$.totals.total").value(28.00));
    }

    @Test
    void quoteFailuresKeepTheirStatusCodes() throws Exception {
        when(orderService.placeOrder(any(), anyString(), any())).thenThrow(new RateLimitExceededException("Rate limit reached.", 42));

        mockMvc.perform(checkout("CH", null, BENCHY_ITEM))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.supportEmail").value("help@example.com"));
    }

    @Test
    void unsupportedCountryIs400() throws Exception {
        when(orderService.placeOrder(any(), anyString(), any()))
                .thenThrow(new IllegalArgumentException("Shipping to DE is not offered. Available: CH"));

        mockMvc.perform(checkout("DE", null, BENCHY_ITEM))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("DE")));
    }

    @Test
    void settingsTellTheCheckoutPageWhatToShow() throws Exception {
        when(orderService.settings()).thenReturn(new OrderSettingsResponse("CHF", new BigDecimal("9.00"), false,
                new BigDecimal("0.081"), 14, List.of("CH")));

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
                .andExpect(jsonPath("$.orderNumber").value("POD-1000"))
                .andExpect(jsonPath("$.items[1].position").value(2));
        mockMvc.perform(get("/api/orders/POD-1000").param("email", "other@example.com"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/orders/POD-1000"))
                .andExpect(status().isBadRequest());
    }

    private static MockHttpServletRequestBuilder checkout(String country, String expectedTotal, String... items) {
        String body = """
                {"customer": {"name": "Anna Muster", "email": "anna@example.com", "street": "Bahnhofstrasse 1",
                              "postalCode": "8001", "city": "Zürich", "country": "%s"},
                 "items": [%s],
                 "expectedTotalChf": %s}
                """.formatted(country, String.join(",", items), expectedTotal == null ? "null" : expectedTotal);
        return post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String item(String modelId, String fileName, int quantity) {
        return """
                {"modelId": "%s", "fileName": "%s", "filament": "PLA", "color": "red", "quality": "medium", "quantity": %d}
                """.formatted(modelId, fileName, quantity);
    }

    private static OrderResponse sampleOrder() {
        Order order = TestOrders.twoItemOrder();
        order.attachPayment("mock", "mock_1", "http://localhost:4200/pay/mock?session=mock_1&order=POD-1000&amount=28.00&currency=CHF");
        return OrderResponse.from(order, false);
    }
}
