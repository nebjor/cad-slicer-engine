package com.slicercore.account;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Limits password logins: a fixed window of fifteen minutes per e-mail address and one of an
 * hour per client address, so a password cannot be guessed online. Every attempt counts,
 * successful or not; beyond either quota the login is refused like a wrong password, which
 * keeps the answer the same for every address. Counting is done by {@link AttemptWindows}.
 */
@Component
public class PasswordLoginRateLimiter {

    static final Duration PER_EMAIL_WINDOW = Duration.ofMinutes(15);
    private static final Duration HOUR = Duration.ofHours(1);

    private final AttemptWindows windows;

    public PasswordLoginRateLimiter(AccountProperties properties, Clock clock) {
        this.windows = new AttemptWindows(clock, PER_EMAIL_WINDOW, properties.passwordLoginsPerEmail(),
                HOUR, properties.passwordLoginsPerClientPerHour());
    }

    /** Records one attempt and tells whether the credentials may be checked at all. */
    public boolean allow(String email, String clientKey) {
        return windows.allow(email, clientKey);
    }
}
