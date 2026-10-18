package com.slicercore.controller;

import com.slicercore.config.RateLimitProperties;
import com.slicercore.service.QuoteRateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Operator endpoints, protected by the shared secret in {@code rate-limit.admin-token}. When no
 * token is configured the endpoints answer 404, so an unconfigured deployment exposes nothing.
 *
 * POST /api/admin/rate-limits/reset[?clientKey=1.2.3.4]   clear one client's windows, or all
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private final QuoteRateLimiter rateLimiter;
    private final RateLimitProperties properties;

    public AdminController(QuoteRateLimiter rateLimiter, RateLimitProperties properties) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @PostMapping("/rate-limits/reset")
    public Map<String, Object> resetRateLimit(
            @RequestHeader(value = ADMIN_TOKEN_HEADER, required = false) String token,
            @RequestParam(required = false) String clientKey) {
        authorize(token);

        if (clientKey == null || clientKey.isBlank()) {
            rateLimiter.resetAll();
            return Map.of("reset", "all");
        }
        boolean existed = rateLimiter.reset(clientKey);
        return Map.of("reset", clientKey, "hadUsage", existed);
    }

    private void authorize(String presented) {
        String expected = properties.adminToken();
        if (expected == null || expected.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] presentedBytes = presented == null ? new byte[0] : presented.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedBytes, presentedBytes)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid admin token.");
        }
    }
}
