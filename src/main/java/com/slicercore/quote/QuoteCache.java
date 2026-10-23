package com.slicercore.quote;

import com.slicercore.slicer.PrintQuality;
import com.slicercore.slicer.SliceQuoteResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Small LRU cache of single-copy slicer results keyed by model content and quality. Every
 * slicer run costs seconds of CPU and counts against the rate limit, so only what changes the
 * slice is part of the key. Pricing is cheap and done per request on top of the cached slice:
 * a different quantity (ADR 0006), a colour with another material price, or another filament
 * scaled by its factors (ADR 0007) never slices again.
 */
@Component
public class QuoteCache {

    private final Clock clock;
    private final Duration timeToLive;
    private final Map<String, Entry> entries;

    public QuoteCache(Clock clock,
                      @Value("${quote.cache.max-entries:256}") int maxEntries,
                      @Value("${quote.cache.ttl-minutes:60}") long ttlMinutes) {
        this.clock = clock;
        this.timeToLive = Duration.ofMinutes(ttlMinutes);
        this.entries = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > maxEntries;
            }
        };
    }

    public static String key(String contentHash, PrintQuality quality) {
        return contentHash + "|" + quality.code();
    }

    public synchronized Optional<SliceQuoteResult> get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.storedAt.plus(timeToLive).isBefore(clock.instant())) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.slice);
    }

    public synchronized void put(String key, SliceQuoteResult slice) {
        entries.put(key, new Entry(slice, clock.instant()));
    }

    public synchronized int size() {
        return entries.size();
    }

    private record Entry(SliceQuoteResult slice, Instant storedAt) {
    }
}
