package com.slicercore.account;

import com.slicercore.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordLoginRateLimiterTest {

    private static final String ANNA = "anna@example.com";
    private static final String CLIENT = "203.0.113.7";

    private final MutableClock clock = MutableClock.at("2026-10-21T10:00:00Z");
    private final PasswordLoginRateLimiter limiter =
            new PasswordLoginRateLimiter(new AccountProperties(true, 15, 30, 3, 10, 5, 20), clock);

    @Test
    void anAddressGetsFiveAttemptsPerFifteenMinutes() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.allow(ANNA, CLIENT)).isTrue();
        }
        assertThat(limiter.allow(ANNA, CLIENT)).isFalse();
        assertThat(limiter.allow(ANNA, "198.51.100.9")).isFalse();

        clock.advance(Duration.ofMinutes(14));
        assertThat(limiter.allow(ANNA, CLIENT)).isFalse();
        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.allow(ANNA, CLIENT)).isTrue();
    }

    @Test
    void aClientGetsTwentyAttemptsAnHourAcrossAllAddresses() {
        for (int i = 0; i < 20; i++) {
            assertThat(limiter.allow("person" + i + "@example.com", CLIENT)).isTrue();
        }

        assertThat(limiter.allow("twenty-first@example.com", CLIENT)).isFalse();
        assertThat(limiter.allow("twenty-first@example.com", "198.51.100.9")).isTrue();

        clock.advance(Duration.ofHours(1));
        assertThat(limiter.allow("twenty-second@example.com", CLIENT)).isTrue();
    }

    @Test
    void aRefusedAttemptConsumesNoQuotaOnTheOtherWindow() {
        for (int i = 0; i < 10; i++) {
            limiter.allow(ANNA, CLIENT);
        }

        // The client's hour window saw five attempts, not ten: fifteen more addresses still fit.
        for (int i = 0; i < 15; i++) {
            assertThat(limiter.allow("other" + i + "@example.com", CLIENT)).isTrue();
        }
        assertThat(limiter.allow("yet-another@example.com", CLIENT)).isFalse();
    }
}
