package com.slicercore.cart;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Cart settings, bound from {@code account.*} next to the account settings.
 *
 * @param cartRetentionDays days without a change after which a cart line and its file are deleted
 */
@ConfigurationProperties(prefix = "account")
public record CartProperties(int cartRetentionDays) {

    public Duration retention() {
        return Duration.ofDays(cartRetentionDays);
    }
}
