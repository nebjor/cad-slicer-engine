package com.slicercore.account;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * In-memory fixed-window counters, in the style of the quote rate limiter: one window per
 * subject (an e-mail address) and one per client address, each with its own length and
 * quota. An attempt counts only when both windows have room, so a refused attempt consumes
 * no quota on the other window. Shared by the login-link and the password-login limiters.
 *
 * State lives in this process only; a restart clears every window.
 */
final class AttemptWindows {

    private static final int PURGE_THRESHOLD = 10_000;

    private final Clock clock;
    private final Duration perSubject;
    private final int perSubjectLimit;
    private final Duration perClient;
    private final int perClientLimit;
    private final Map<String, Window> windows = new HashMap<>();

    AttemptWindows(Clock clock, Duration perSubject, int perSubjectLimit, Duration perClient, int perClientLimit) {
        this.clock = clock;
        this.perSubject = perSubject;
        this.perSubjectLimit = perSubjectLimit;
        this.perClient = perClient;
        this.perClientLimit = perClientLimit;
    }

    /** Records one attempt and tells whether it is within both quotas. */
    synchronized boolean allow(String subject, String clientKey) {
        Instant now = clock.instant();
        purgeIfLarge(now);
        Window bySubject = windows.computeIfAbsent("subject:" + subject, key -> new Window());
        Window byClient = windows.computeIfAbsent("client:" + clientKey, key -> new Window());
        if (!bySubject.fits(now, perSubject, perSubjectLimit) || !byClient.fits(now, perClient, perClientLimit)) {
            return false;
        }
        bySubject.record(now, perSubject);
        byClient.record(now, perClient);
        return true;
    }

    private void purgeIfLarge(Instant now) {
        if (windows.size() < PURGE_THRESHOLD) {
            return;
        }
        Duration longest = perSubject.compareTo(perClient) > 0 ? perSubject : perClient;
        windows.entrySet().removeIf(entry -> entry.getValue().isExpired(now, longest));
    }

    private static final class Window {
        private Instant openedAt;
        private int count;

        boolean fits(Instant now, Duration length, int limit) {
            return isExpired(now, length) || count < limit;
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
