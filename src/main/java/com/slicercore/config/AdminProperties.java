package com.slicercore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The single owner login for the admin API, bound from {@code admin.*}. A blank password keeps
 * the admin endpoints unreachable, so an unconfigured deployment exposes nothing.
 */
@ConfigurationProperties(prefix = "admin")
public record AdminProperties(String username, String password) {

    public boolean enabled() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }
}
