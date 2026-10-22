package com.slicercore.account;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Limits login links: a fixed window per e-mail address (the link validity) and one per
 * client address (an hour). A request beyond either quota is answered as usual but sends no
 * mail, so neither an attacker nor the address owner learns anything from the response, and
 * a mailbox cannot be flooded. Counting is done by {@link AttemptWindows}.
 */
@Component
public class LoginLinkRateLimiter {

    private static final Duration HOUR = Duration.ofHours(1);

    private final AttemptWindows windows;

    public LoginLinkRateLimiter(AccountProperties properties, Clock clock) {
        this.windows = new AttemptWindows(clock, properties.loginLinkValidity(), properties.loginLinksPerEmail(),
                HOUR, properties.loginLinksPerClientPerHour());
    }

    /** Records one request and tells whether a link may be sent for it. */
    public boolean allow(String email, String clientKey) {
        return windows.allow(email, clientKey);
    }
}
