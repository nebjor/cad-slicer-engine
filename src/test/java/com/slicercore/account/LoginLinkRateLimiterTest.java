package com.slicercore.account;

import com.slicercore.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LoginLinkRateLimiterTest {

    private static final String ANNA = "anna@example.com";
    private static final String CLIENT = "203.0.113.7";

    private final MutableClock clock = MutableClock.at("2026-10-21T10:00:00Z");
    private final LoginLinkRateLimiter limiter = new LoginLinkRateLimiter(new AccountProperties(true, 15, 30, 3, 10), clock);

    @Test
    void anAddressGetsThreeLinksPerValidityWindow() {
        assertThat(allowed(ANNA, CLIENT, 3)).isEqualTo(3);
        assertThat(limiter.allow(ANNA, CLIENT)).isFalse();

        clock.advance(Duration.ofMinutes(15));
        assertThat(limiter.allow(ANNA, CLIENT)).isTrue();
    }

    @Test
    void addressesAreCountedSeparately() {
        allowed(ANNA, CLIENT, 3);

        assertThat(limiter.allow("ben@example.com", CLIENT)).isTrue();
    }

    @Test
    void aClientGetsTenLinksAnHourAcrossAllAddresses() {
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.allow("person" + i + "@example.com", CLIENT)).isTrue();
        }

        assertThat(limiter.allow("eleventh@example.com", CLIENT)).isFalse();
        assertThat(limiter.allow("eleventh@example.com", "198.51.100.9")).isTrue();

        clock.advance(Duration.ofHours(1));
        assertThat(limiter.allow("twelfth@example.com", CLIENT)).isTrue();
    }

    @Test
    void aRefusedRequestConsumesNoQuotaOnTheOtherWindow() {
        allowed(ANNA, CLIENT, 3);
        for (int i = 0; i < 5; i++) {
            limiter.allow(ANNA, CLIENT);
        }

        // The client's hour window saw three sends, not eight: seven more addresses still fit.
        for (int i = 0; i < 7; i++) {
            assertThat(limiter.allow("other" + i + "@example.com", CLIENT)).isTrue();
        }
        assertThat(limiter.allow("yet-another@example.com", CLIENT)).isFalse();
    }

    private int allowed(String email, String client, int times) {
        int count = 0;
        for (int i = 0; i < times; i++) {
            if (limiter.allow(email, client)) {
                count++;
            }
        }
        return count;
    }
}
