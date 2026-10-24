package com.slicercore.quote;

import com.slicercore.config.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, per-client limiter for slicer starts: a one-minute and a one-hour fixed window.
 *
 * A window opens with a client's first run and closes after its duration; the count then
 * starts over. State lives in this process only, so a restart clears every window, which is
 * acceptable for a single-instance deployment and documented for the operator.
 */
@Component
public class QuoteRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(QuoteRateLimiter.class);
    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration HOUR = Duration.ofHours(1);
    private static final int PURGE_THRESHOLD = 10_000;

    private final RateLimitProperties properties;
    private final Clock clock;
    private final Map<String, ClientUsage> usageByClient = new ConcurrentHashMap<>();

    public QuoteRateLimiter(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Records one slicer start for the client or throws when a window is exhausted. Call this
     * only when a run will actually start, never for cache hits.
     */
    public void recordStart(String clientKey) {
        if (!properties.enabled()) {
            return;
        }

        Instant now = clock.instant();
        purgeIfLarge(now);

        ClientUsage usage = usageByClient.computeIfAbsent(clientKey, key -> new ClientUsage());
        synchronized (usage) {
            long retryAfter = Math.max(
                    usage.minute.secondsUntilAllowed(now, MINUTE, properties.perMinute()),
                    usage.hour.secondsUntilAllowed(now, HOUR, properties.perHour()));
            if (retryAfter > 0) {
                log.info("Rate limit hit for client {} ({}/min, {}/h); retry after {} s",
                        clientKey, properties.perMinute(), properties.perHour(), retryAfter);
                throw new RateLimitExceededException(
                        "Rate limit reached: at most " + properties.perMinute() + " quotes per minute and "
                                + properties.perHour() + " per hour. Try again in " + retryAfter + " seconds.",
                        retryAfter);
            }
            usage.minute.record(now, MINUTE);
            usage.hour.record(now, HOUR);
        }
    }

    /** Clears the windows of one client, for support cases. */
    public boolean reset(String clientKey) {
        return usageByClient.remove(clientKey) != null;
    }

    /** Clears every window. */
    public void resetAll() {
        usageByClient.clear();
    }

    private void purgeIfLarge(Instant now) {
        if (usageByClient.size() < PURGE_THRESHOLD) {
            return;
        }
        usageByClient.entrySet().removeIf(entry -> {
            ClientUsage usage = entry.getValue();
            synchronized (usage) {
                return usage.hour.isExpired(now, HOUR);
            }
        });
    }

    private static final class ClientUsage {
        private final Window minute = new Window();
        private final Window hour = new Window();
    }

    private static final class Window {
        private Instant openedAt;
        private int count;

        /** Zero when another start fits in the window, otherwise the seconds until it reopens. */
        long secondsUntilAllowed(Instant now, Duration length, int limit) {
            if (isExpired(now, length) || count < limit) {
                return 0;
            }
            return Math.max(1, Duration.between(now, openedAt.plus(length)).toSeconds());
        }

        void record(Instant now, Duration length) {
            if (isExpired(now, length)) {
                openedAt = now;
                count = 0;
            }
            count++;
        }

        boolean isExpired(Instant now, Duration length) {
            return openedAt == null || !now.isBefore(openedAt.plus(length));
        }
    }
}
