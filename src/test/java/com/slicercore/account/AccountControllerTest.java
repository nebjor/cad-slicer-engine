package com.slicercore.account;

import com.slicercore.config.RateLimitProperties;
import com.slicercore.config.SecurityConfig;
import com.slicercore.order.OrderResponse;
import com.slicercore.support.TestOrders;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract the frontend's account pages are built against: status codes, problem
 * codes, the cookie and its attributes. The session resolver and the cookie helper are real;
 * the services behind them are mocked.
 */
@WebMvcTest(AccountController.class)
@Import({AccountControllerTest.Config.class, SecurityConfig.class, SessionCookies.class, AccountSessionResolver.class})
class AccountControllerTest {

    @TestConfiguration
    static class Config {
        @Bean
        RateLimitProperties rateLimitProperties() {
            return new RateLimitProperties(true, 10, 100, false, "");
        }

        @Bean
        AccountProperties accountProperties() {
            return new AccountProperties(true, 15, 30, 3, 10);
        }
    }

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final String SECRET = "cookie-secret";
    private static final AccountResponse ANNA = new AccountResponse("anna@example.com", "Anna Muster",
            "Bahnhofstrasse 1", "8001", "Zürich", "CH", Instant.parse("2026-10-01T10:00:00Z"),
            Instant.parse("2026-10-21T10:00:00Z"));

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    LoginLinkService loginLinks;

    @MockitoBean
    AccountService accounts;

    @Test
    void requestingALoginLinkIsAcceptedWithoutRevealingAnything() throws Exception {
        mockMvc.perform(post("/api/account/login-links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"Anna@Example.com\"}"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        verify(loginLinks).requestLink("Anna@Example.com", "127.0.0.1");
    }

    @Test
    void aMalformedAddressIs400WithAnErrorMap() throws Exception {
        mockMvc.perform(post("/api/account/login-links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists());

        verifyNoInteractions(loginLinks);
    }

    /** The consumes mismatch is decided before a handler is chosen, so the 415 carries no body. */
    @Test
    void formPostsAreRefusedWith415AsTheCsrfGuard() throws Exception {
        mockMvc.perform(post("/api/account/login-links").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("email=anna%40example.com"))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(post("/api/account/sessions").contentType(MediaType.TEXT_PLAIN).content("token"))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(put("/api/account").cookie(sessionCookie()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("name=x"))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(loginLinks, accounts);
    }

    @Test
    void openingASessionSetsTheCookieAndReturnsTheAccount() throws Exception {
        when(accounts.openSession("tok")).thenReturn(new AccountService.OpenedSession(ANNA, SECRET));

        mockMvc.perform(post("/api/account/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"tok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("anna@example.com"))
                .andExpect(jsonPath("$.name").value("Anna Muster"))
                .andExpect(jsonPath("$.postalCode").value("8001"))
                .andExpect(jsonPath("$.lastLoginAt").value("2026-10-21T10:00:00Z"))
                .andExpect(cookie().value("pod_session", SECRET))
                .andExpect(cookie().httpOnly("pod_session", true))
                .andExpect(cookie().secure("pod_session", true))
                .andExpect(cookie().path("pod_session", "/"))
                .andExpect(cookie().maxAge("pod_session", 30 * 24 * 3600))
                .andExpect(header().string("Set-Cookie", allOf(containsString("SameSite=Lax"), containsString("HttpOnly"))));
    }

    @Test
    void aBadTokenIs400WithTheInvalidTokenCode() throws Exception {
        when(accounts.openSession("stale")).thenThrow(new InvalidLoginTokenException());

        mockMvc.perform(post("/api/account/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"stale\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-token"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        mockMvc.perform(post("/api/account/sessions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.token").exists());
    }

    @Test
    void withoutASessionTheAccountIs401WithTheNotLoggedInCode() throws Exception {
        mockMvc.perform(get("/api/account"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("not-logged-in"))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
        mockMvc.perform(get("/api/account/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("not-logged-in"));
        mockMvc.perform(delete("/api/account"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/account").contentType(MediaType.APPLICATION_JSON).content(details()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(accounts);
    }

    @Test
    void aStaleCookieIs401AndIsCleared() throws Exception {
        when(accounts.resolve(SECRET)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/account").cookie(sessionCookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("not-logged-in"))
                .andExpect(cookie().value("pod_session", ""))
                .andExpect(cookie().maxAge("pod_session", 0));
    }

    @Test
    void aLiveSessionReadsTheAccountWithoutTouchingTheCookie() throws Exception {
        loggedIn(false);
        when(accounts.account(ACCOUNT_ID)).thenReturn(ANNA);

        mockMvc.perform(get("/api/account").cookie(sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("anna@example.com"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-01T10:00:00Z"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void anExtendedSessionResendsTheCookie() throws Exception {
        loggedIn(true);
        when(accounts.account(ACCOUNT_ID)).thenReturn(ANNA);

        mockMvc.perform(get("/api/account").cookie(sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(cookie().value("pod_session", SECRET))
                .andExpect(cookie().maxAge("pod_session", 30 * 24 * 3600));
    }

    @Test
    void updatingTheAccountNormalisesAndValidatesLikeTheCheckout() throws Exception {
        loggedIn(false);
        when(accounts.update(any(), any())).thenReturn(ANNA);

        mockMvc.perform(put("/api/account").cookie(sessionCookie()).contentType(MediaType.APPLICATION_JSON).content(details()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("anna@example.com"));
        verify(accounts).update(ACCOUNT_ID, new Account.Details("Anna Muster", "Bahnhofstrasse 1", "8001", "Zürich", "CH"));

        mockMvc.perform(put("/api/account").cookie(sessionCookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"\", \"street\": \"Bahnhofstrasse 1\", \"postalCode\": \"8001\", \"city\": \"Zürich\", \"country\": \"Switzerland\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.country").value(containsString("two-letter")));
    }

    @Test
    void theAccountsOrdersAreListedAsOrderResponses() throws Exception {
        loggedIn(false);
        when(accounts.orders(ACCOUNT_ID)).thenReturn(List.of(
                OrderResponse.from(TestOrders.twoItemOrder(), false), OrderResponse.from(TestOrders.benchyOrder(), false)));

        mockMvc.perform(get("/api/account/orders").cookie(sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].orderNumber").value("POD-1000"))
                .andExpect(jsonPath("$[0].items.length()").value(2))
                .andExpect(jsonPath("$[0].totals.total").value(28.00))
                .andExpect(jsonPath("$[1].items.length()").value(1));
    }

    @Test
    void loggingOutClearsTheCookieAndClosesTheSession() throws Exception {
        mockMvc.perform(delete("/api/account/sessions").cookie(sessionCookie()))
                .andExpect(status().isNoContent())
                .andExpect(cookie().value("pod_session", ""))
                .andExpect(cookie().maxAge("pod_session", 0));
        verify(accounts).closeSession(SECRET);

        // Without a cookie the result is the same: logged out.
        mockMvc.perform(delete("/api/account/sessions"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("pod_session", 0));
    }

    @Test
    void deletingTheAccountNeedsASessionAndClearsTheCookie() throws Exception {
        loggedIn(false);

        mockMvc.perform(delete("/api/account").cookie(sessionCookie()))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("pod_session", 0))
                .andExpect(header().string("Set-Cookie", not(containsString(SECRET))));
        verify(accounts).deleteAccount(ACCOUNT_ID);
    }

    private void loggedIn(boolean extended) {
        when(accounts.resolve(SECRET)).thenReturn(Optional.of(new AccountService.ResolvedSession(ACCOUNT_ID, extended)));
    }

    private static Cookie sessionCookie() {
        return new Cookie("pod_session", SECRET);
    }

    private static String details() {
        return "{\"name\": \" Anna Muster \", \"street\": \"Bahnhofstrasse 1\", \"postalCode\": \"8001\", "
                + "\"city\": \"Zürich\", \"country\": \"ch\"}";
    }
}
