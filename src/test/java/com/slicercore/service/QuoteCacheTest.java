package com.slicercore.service;

import com.slicercore.model.BoundingBox;
import com.slicercore.model.FilamentType;
import com.slicercore.model.PrintQuality;
import com.slicercore.model.SliceQuoteResult;
import com.slicercore.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

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
    void returnsStoredSliceUntilItExpires() {
        SliceQuoteResult quote = slice("a");
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
        cache.put("a", slice("a"));
        cache.put("b", slice("b"));
        cache.get("a");
        cache.put("c", slice("c"));

        assertThat(cache.get("b")).isEmpty();
        assertThat(cache.get("a")).isPresent();
        assertThat(cache.get("c")).isPresent();
        assertThat(cache.size()).isEqualTo(2);
    }

    private static SliceQuoteResult slice(String name) {
        return new SliceQuoteResult(name, List.of(), null, 0, "Success.", 0.2, 1, 1, 100, 4, 10, 0, BoundingBox.EMPTY,
                List.of(), null);
    }
}
