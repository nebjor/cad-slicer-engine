package com.slicercore.service;

import com.slicercore.config.RateLimitProperties;
import com.slicercore.model.RateLimitExceededException;
import com.slicercore.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuoteRateLimiterTest {

    private static final String CLIENT = "203.0.113.7";

    private final MutableClock clock = MutableClock.at("2026-10-18T12:00:00Z");
    private final QuoteRateLimiter limiter = new QuoteRateLimiter(
            new RateLimitProperties(true, 3, 5, false, "help@example.com"), clock);

    @Test
    void allowsTheMinuteQuotaThenRejectsWithRetryAfter() {
        record(3);

        assertThatThrownBy(() -> limiter.recordStart(CLIENT))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("3 quotes per minute")
                .extracting(e -> ((RateLimitExceededException) e).retryAfterSeconds())
                .satisfies(retry -> assertThat((Long) retry).isBetween(1L, 60L));
    }

    @Test
    void minuteWindowReopensAfterSixtySeconds() {
        record(3);
        clock.advance(Duration.ofSeconds(61));

        assertThatCode(() -> limiter.recordStart(CLIENT)).doesNotThrowAnyException();
    }

    @Test
    void hourQuotaCapsAcrossMinutes() {
        record(3);
        clock.advance(Duration.ofSeconds(61));
        record(2);

        assertThatThrownBy(() -> limiter.recordStart(CLIENT))
                .isInstanceOf(RateLimitExceededException.class)
                .extracting(e -> ((RateLimitExceededException) e).retryAfterSeconds())
                .satisfies(retry -> assertThat((Long) retry).isBetween(3500L, 3600L));
    }

    @Test
    void clientsAreCountedSeparately() {
        record(3);

        assertThatCode(() -> limiter.recordStart("198.51.100.9")).doesNotThrowAnyException();
    }

    @Test
    void resetClearsOneClient() {
        record(3);

        assertThat(limiter.reset(CLIENT)).isTrue();
        assertThat(limiter.reset(CLIENT)).isFalse();
        assertThatCode(() -> limiter.recordStart(CLIENT)).doesNotThrowAnyException();
    }

    @Test
    void disabledLimiterNeverRejects() {
        QuoteRateLimiter disabled = new QuoteRateLimiter(
                new RateLimitProperties(false, 1, 1, false, ""), clock);

        for (int i = 0; i < 50; i++) {
            disabled.recordStart(CLIENT);
        }
    }

    private void record(int times) {
        for (int i = 0; i < times; i++) {
            limiter.recordStart(CLIENT);
        }
    }
}
