package com.slicercore.service;

import com.slicercore.model.FilamentType;
import com.slicercore.model.PrintQuality;
import com.slicercore.model.SliceQuoteResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Small LRU cache of single-copy slicer results keyed by model content, filament and quality.
 * A customer who flips PLA to PETG and back must not start the slicer twice: every run costs
 * seconds of CPU and counts against the rate limit. Pricing is cheap and done per request on
 * top of the cached slice, so neither a colour with another material price nor a different
 * quantity slices again (ADR 0006).
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

    public static String key(String contentHash, FilamentType filament, PrintQuality quality) {
        return contentHash + "|" + filament.name() + "|" + quality.code();
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
