package com.slicercore.account;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCookiesTest {

    @Test
    void theSessionCookieIsHttpOnlySecureLaxSiteWideAndLivesAsLongAsTheSession() {
        ResponseCookie cookie = new SessionCookies(new AccountProperties(true, 15, 30, 3, 10)).issue("secret");

        assertThat(cookie.getName()).isEqualTo("pod_session");
        assertThat(cookie.getValue()).isEqualTo("secret");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getMaxAge().toDays()).isEqualTo(30);
    }

    @Test
    void localHttpDevelopmentCanDropTheSecureFlag() {
        assertThat(new SessionCookies(new AccountProperties(false, 15, 30, 3, 10)).issue("secret").isSecure()).isFalse();
    }

    @Test
    void clearingSendsAnEmptyCookieThatExpiresAtOnce() {
        ResponseCookie cleared = new SessionCookies(new AccountProperties(true, 15, 30, 3, 10)).clear();

        assertThat(cleared.getValue()).isEmpty();
        assertThat(cleared.getMaxAge().isZero()).isTrue();
        assertThat(cleared.toString()).contains("Max-Age=0");
    }

    @Test
    void readsOnlyANonBlankSessionCookie() {
        SessionCookies cookies = new SessionCookies(new AccountProperties(true, 15, 30, 3, 10));

        assertThat(cookies.read(new MockHttpServletRequest())).isEmpty();
        MockHttpServletRequest blank = new MockHttpServletRequest();
        blank.setCookies(new Cookie("pod_session", ""));
        assertThat(cookies.read(blank)).isEmpty();
        MockHttpServletRequest present = new MockHttpServletRequest();
        present.setCookies(new Cookie("other", "x"), new Cookie("pod_session", "secret"));
        assertThat(cookies.read(present)).contains("secret");
    }
}
