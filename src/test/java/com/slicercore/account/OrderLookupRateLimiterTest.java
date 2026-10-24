package com.slicercore.account;

import com.slicercore.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderLookupRateLimiterTest {

    private final MutableClock clock = MutableClock.at("2026-10-24T10:00:00Z");
    private final OrderLookupRateLimiter limiter =
            new OrderLookupRateLimiter(new AccountProperties(true, 15, 30, 3, 10, 5, 20, 3, 100), clock);

    @Test
    void anAccountMayLookUpThreeOrdersPerMinuteAndThenWaits() {
        UUID account = UUID.randomUUID();
        assertThat(limiter.allow(account, "203.0.113.7")).isTrue();
        assertThat(limiter.allow(account, "203.0.113.7")).isTrue();
        assertThat(limiter.allow(account, "203.0.113.7")).isTrue();
        assertThat(limiter.allow(account, "203.0.113.7")).isFalse();
        assertThat(limiter.allow(UUID.randomUUID(), "198.51.100.1")).as("another account elsewhere").isTrue();

        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.allow(account, "203.0.113.7")).isTrue();
    }
}
