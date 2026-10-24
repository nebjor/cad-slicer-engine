package com.slicercore.account;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Limits order lookups under the account: one minute per account and one minute per client
 * address, so order numbers cannot be enumerated quickly. Counting is done by {@link AttemptWindows}.
 */
@Component
public class OrderLookupRateLimiter {

    static final Duration WINDOW = Duration.ofMinutes(1);

    private final AttemptWindows windows;

    public OrderLookupRateLimiter(AccountProperties properties, Clock clock) {
        this.windows = new AttemptWindows(clock, WINDOW, properties.orderLookupsPerAccountPerMinute(),
                WINDOW, properties.orderLookupsPerClientPerMinute());
    }

    /** Records one lookup and tells whether it is within both quotas. */
    public boolean allow(UUID accountId, String clientKey) {
        return windows.allow(accountId.toString(), clientKey);
    }
}
