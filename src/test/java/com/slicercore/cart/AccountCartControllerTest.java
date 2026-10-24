package com.slicercore.cart;

import com.slicercore.config.RateLimitProperties;
import com.slicercore.config.SecurityConfig;
import com.slicercore.upload.UploadedModel;
import com.slicercore.account.NotLoggedInException;
import com.slicercore.account.SessionProblems;
import com.slicercore.order.CurrentAccount;
import com.slicercore.order.OrderModelExpiredException;
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

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP contract of the account cart: sessions, status codes, problem codes and the JSON shape. */
@WebMvcTest(AccountCartController.class)
@Import({AccountCartControllerTest.Config.class, SecurityConfig.class, SessionProblems.class})
class AccountCartControllerTest {

    @TestConfiguration
    static class Config {
        @Bean
        RateLimitProperties rateLimitProperties() {
            return new RateLimitProperties(true, 10, 100, false, "");
        }
    }

    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID ITEM = UUID.randomUUID();
    private static final String MODEL = "a".repeat(64);
    private static final CartResponse CART = new CartResponse(List.of(new CartItemResponse(ITEM, 1, "benchy.stl", 28,
            "PLA", "red", "standard", 2, Map.of("totalChf", 12.5), Instant.parse("2026-10-22T10:00:00Z"))));
    private static final String LINE = """
            {"modelId": "%s", "fileName": "benchy.stl", "filament": "PLA", "colorId": "red",
             "quality": "standard", "quantity": 2, "quote": {"totalChf": 12.5}}""".formatted(MODEL);

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AccountCartService carts;

    @MockitoBean
    CurrentAccount currentAccount;

    private void signedIn() {
        when(currentAccount.require(any())).thenReturn(ACCOUNT);
    }

    private static MockHttpServletRequestBuilder putCart(String body) {
        return put("/api/account/cart").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void everyEndpointAnswers401NotLoggedInWithoutASession() throws Exception {
        when(currentAccount.require(any())).thenThrow(new NotLoggedInException());

        for (MockHttpServletRequestBuilder request : List.of(get("/api/account/cart"), delete("/api/account/cart"),
                post("/api/account/cart/" + ITEM + "/model"), putCart("{\"items\": []}"))) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("not-logged-in"));
        }
        verifyNoInteractions(carts);
    }

    @Test
    void getReturnsTheCart() throws Exception {
        signedIn();
        when(carts.cart(ACCOUNT)).thenReturn(CART);

        mockMvc.perform(get("/api/account/cart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(ITEM.toString()))
                .andExpect(jsonPath("$.items[0].position").value(1))
                .andExpect(jsonPath("$.items[0].fileName").value("benchy.stl"))
                .andExpect(jsonPath("$.items[0].sizeBytes").value(28))
                .andExpect(jsonPath("$.items[0].quote.totalChf").value(12.5))
                .andExpect(jsonPath("$.items[0].updatedAt").value("2026-10-22T10:00:00Z"));
    }

    @Test
    void putReplacesTheCartAndReturnsIt() throws Exception {
        signedIn();
        when(carts.replace(eq(ACCOUNT), any())).thenReturn(CART);

        mockMvc.perform(putCart("{\"items\": [" + LINE + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));

        verify(carts).replace(eq(ACCOUNT), argThat(lines -> lines.size() == 1
                && MODEL.equals(lines.get(0).modelId()) && lines.get(0).quantity() == 2
                && Map.of("totalChf", 12.5).equals(lines.get(0).quote())));
    }

    @Test
    void putRejectsMoreThanTenLinesAndBrokenLinesWith400() throws Exception {
        signedIn();
        String eleven = "{\"items\": [" + String.join(",", Collections.nCopies(11, LINE)) + "]}";
        mockMvc.perform(putCart(eleven)).andExpect(status().isBadRequest());
        mockMvc.perform(putCart("{\"items\": [{\"modelId\": \"" + MODEL + "\", \"quantity\": 0}]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(carts);
    }

    @Test
    void putWithALineThatNamesNoFileIsA400FromTheService() throws Exception {
        signedIn();
        when(carts.replace(eq(ACCOUNT), any()))
                .thenThrow(new IllegalArgumentException("Cart line 1 needs either an id or a modelId."));

        mockMvc.perform(putCart("{\"items\": [" + LINE.replace("\"modelId\": \"" + MODEL + "\",", "") + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Cart line 1 needs either an id or a modelId."));
    }

    @Test
    void anExpiredModelIs404ModelExpiredWithTheLineIndex() throws Exception {
        signedIn();
        when(carts.replace(eq(ACCOUNT), any())).thenThrow(new OrderModelExpiredException(1));

        mockMvc.perform(putCart("{\"items\": [" + LINE + "]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("model-expired"))
                .andExpect(jsonPath("$.itemIndex").value(1));
    }

    @Test
    void anUnknownLineIs404CartItemNotFound() throws Exception {
        signedIn();
        when(carts.replace(eq(ACCOUNT), any())).thenThrow(new CartItemNotFoundException());
        when(carts.registerModel(ACCOUNT, ITEM)).thenThrow(new CartItemNotFoundException());

        mockMvc.perform(putCart("{\"items\": [" + LINE + "]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("cart-item-not-found"));
        mockMvc.perform(post("/api/account/cart/" + ITEM + "/model"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("cart-item-not-found"));
    }

    @Test
    void registeringAModelReturnsTheFreshUploadId() throws Exception {
        signedIn();
        when(carts.registerModel(ACCOUNT, ITEM)).thenReturn(new UploadedModel(MODEL, "benchy.stl", 28));

        mockMvc.perform(post("/api/account/cart/" + ITEM + "/model"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelId").value(MODEL))
                .andExpect(jsonPath("$.fileName").value("benchy.stl"))
                .andExpect(jsonPath("$.sizeBytes").value(28));
    }

    @Test
    void deleteEmptiesTheCart() throws Exception {
        signedIn();

        mockMvc.perform(delete("/api/account/cart")).andExpect(status().isNoContent());

        verify(carts).clear(ACCOUNT);
    }
}
