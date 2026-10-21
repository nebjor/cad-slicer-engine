package com.slicercore.account;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * In-memory limiter for login links, in the style of the quote rate limiter: a fixed window
 * per e-mail address (the link validity) and one per client address (an hour). A request
 * beyond either quota is answered as usual but sends no mail, so neither an attacker nor the
 * address owner learns anything from the response, and a mailbox cannot be flooded.
 *
 * State lives in this process only; a restart clears every window.
 */
@Component
public class LoginLinkRateLimiter {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final int PURGE_THRESHOLD = 10_000;

    private final AccountProperties properties;
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();

    public LoginLinkRateLimiter(AccountProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Records one request and tells whether a link may be sent for it. */
    public synchronized boolean allow(String email, String clientKey) {
        Instant now = clock.instant();
        purgeIfLarge(now);
        Duration perEmail = properties.loginLinkValidity();
        Window byEmail = windows.computeIfAbsent("email:" + email, key -> new Window());
        Window byClient = windows.computeIfAbsent("client:" + clientKey, key -> new Window());
        if (!byEmail.fits(now, perEmail, properties.loginLinksPerEmail())
                || !byClient.fits(now, HOUR, properties.loginLinksPerClientPerHour())) {
            return false;
        }
        byEmail.record(now, perEmail);
        byClient.record(now, HOUR);
        return true;
    }

    private void purgeIfLarge(Instant now) {
        if (windows.size() < PURGE_THRESHOLD) {
            return;
        }
        windows.entrySet().removeIf(entry -> entry.getValue().isExpired(now, HOUR));
    }

    private static final class Window {
        private Instant openedAt;
        private int count;

        boolean fits(Instant now, Duration length, int limit) {
            return isExpired(now, length) || count < limit;
        }

        void record(Instant now, Duration length) {
            if (isExpired(now, length)) {
                openedAt = now;
                count = 0;
            }
            count++;
        }

        boolean isExpired(Instant now, Duration length) {
            return openedAt == null || !now.isBefore(openedAt.plus(length));
        }
    }
}
