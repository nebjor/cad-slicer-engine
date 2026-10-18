package com.slicercore.service;

import com.slicercore.model.BoundingBox;
import com.slicercore.model.FilamentType;
import com.slicercore.model.PrintQuality;
import com.slicercore.model.QuoteResponse;
import com.slicercore.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class QuoteCacheTest {

    private final MutableClock clock = MutableClock.at("2026-10-18T12:00:00Z");
    private final QuoteCache cache = new QuoteCache(clock, 2, 60);

    @Test
    void keyDistinguishesModelAndParameters() {
        String base = QuoteCache.key("abc", FilamentType.PLA, PrintQuality.MEDIUM, 1);

        assertThat(QuoteCache.key("abc", FilamentType.PLA, PrintQuality.MEDIUM, 1)).isEqualTo(base);
        assertThat(QuoteCache.key("abd", FilamentType.PLA, PrintQuality.MEDIUM, 1)).isNotEqualTo(base);
        assertThat(QuoteCache.key("abc", FilamentType.PETG, PrintQuality.MEDIUM, 1)).isNotEqualTo(base);
        assertThat(QuoteCache.key("abc", FilamentType.PLA, PrintQuality.HIGH, 1)).isNotEqualTo(base);
        assertThat(QuoteCache.key("abc", FilamentType.PLA, PrintQuality.MEDIUM, 2)).isNotEqualTo(base);
    }

    @Test
    void returnsStoredQuoteUntilItExpires() {
        QuoteResponse quote = quote("a");
        cache.put("a", quote);

        assertThat(cache.get("a")).contains(quote);
        clock.advance(Duration.ofMinutes(59));
        assertThat(cache.get("a")).contains(quote);
        clock.advance(Duration.ofMinutes(2));
        assertThat(cache.get("a")).isEmpty();
        assertThat(cache.size()).isZero();
    }

    @Test
    void evictsLeastRecentlyUsedEntryBeyondCapacity() {
        cache.put("a", quote("a"));
        cache.put("b", quote("b"));
        cache.get("a");
        cache.put("c", quote("c"));

        assertThat(cache.get("b")).isEmpty();
        assertThat(cache.get("a")).isPresent();
        assertThat(cache.get("c")).isPresent();
        assertThat(cache.size()).isEqualTo(2);
    }

    private static QuoteResponse quote(String name) {
        return new QuoteResponse(name, "PLA", null, "medium", 1, "A1", 0.2, 1, 100, 100, 10, 1, 1,
                BoundingBox.EMPTY, 4, 1, 1, 5, 3, 8, "CHF", 3);
    }
}
