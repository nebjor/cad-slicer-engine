package com.slicercore.account;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import java.time.Duration;
import java.util.Optional;

/**
 * The {@code pod_session} cookie: HttpOnly so scripts never see the secret, SameSite=Lax so
 * other sites cannot send it along with their requests, Secure unless local development
 * says otherwise, and valid for the session length. Re-sent when the session is extended.
 */
@Component
public class SessionCookies {

    public static final String NAME = "pod_session";

    private final AccountProperties props;

    public SessionCookies(AccountProperties props) {
        this.props = props;
    }

    public Optional<String> read(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, NAME);
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(cookie.getValue());
    }

    public ResponseCookie issue(String sessionSecret) {
        return build(sessionSecret, props.sessionLength());
    }

    public ResponseCookie clear() {
        return build("", Duration.ZERO);
    }

    private ResponseCookie build(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
