package com.slicercore.quote;

import com.slicercore.catalog.PrintQuality;
import com.slicercore.catalog.BoundingBox;
import com.slicercore.slicer.SliceQuoteResult;
import com.slicercore.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuoteCacheTest {

    private final MutableClock clock = MutableClock.at("2026-10-18T12:00:00Z");
    private final QuoteCache cache = new QuoteCache(clock, 2, 60);

    @Test
    void keyDistinguishesModelAndQuality() {
        String base = QuoteCache.key("abc", PrintQuality.MEDIUM);

        assertThat(QuoteCache.key("abc", PrintQuality.MEDIUM)).isEqualTo(base);
        assertThat(QuoteCache.key("abd", PrintQuality.MEDIUM)).isNotEqualTo(base);
        assertThat(QuoteCache.key("abc", PrintQuality.HIGH)).isNotEqualTo(base);
        // Neither filament, colour nor quantity is part of the key: all three are priced from the one slice.
        assertThat(base).isEqualTo("abc|medium");
    }

    @Test
    void returnsStoredSliceUntilItExpires() {
        SliceQuoteResult quote = slice();
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
        cache.put("a", slice());
        cache.put("b", slice());
        cache.get("a");
        cache.put("c", slice());

        assertThat(cache.get("b")).isEmpty();
        assertThat(cache.get("a")).isPresent();
        assertThat(cache.get("c")).isPresent();
        assertThat(cache.size()).isEqualTo(2);
    }

    private static SliceQuoteResult slice() {
        return new SliceQuoteResult(List.of(), 0, "Success.", 0.2, 1, 100, 4, 10, 0, BoundingBox.EMPTY, List.of(), null);
    }
}
