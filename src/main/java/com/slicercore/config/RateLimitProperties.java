package com.slicercore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-client limits on how many slicer runs may be started, bound from {@code rate-limit.*}.
 * Cached quotes do not count; only requests that actually start the slicer do.
 *
 * @param enabled               switch the limiter off entirely (local experiments, load tests)
 * @param perMinute             slicer starts allowed per client within a one-minute window
 * @param perHour               slicer starts allowed per client within a one-hour window
 * @param trustForwardedHeaders use the first X-Forwarded-For address as the client key; only
 *                              enable behind a reverse proxy that overwrites that header
 * @param supportEmail          address shown to clients that hit the limit
 * @param adminToken            shared secret for the reset endpoint; blank disables it
 */
@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitProperties(
        boolean enabled,
        int perMinute,
        int perHour,
        boolean trustForwardedHeaders,
        String supportEmail,
        String adminToken) {
}
