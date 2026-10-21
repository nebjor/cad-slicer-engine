package com.slicercore.config;

import jakarta.servlet.http.HttpServletRequest;
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
 */
@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitProperties(
        boolean enabled,
        int perMinute,
        int perHour,
        boolean trustForwardedHeaders,
        String supportEmail) {

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    /**
     * The address a request is counted against. X-Forwarded-For is only honoured when the
     * operator confirmed a trusted reverse proxy sets it, because any client can send that header.
     */
    public String clientKey(HttpServletRequest request) {
        if (trustForwardedHeaders) {
            String forwardedFor = request.getHeader(FORWARDED_FOR);
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return forwardedFor.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
