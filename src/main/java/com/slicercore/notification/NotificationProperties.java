package com.slicercore.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Who the shop writes to and as whom, bound from {@code notifications.*}. SMTP itself is
 * Spring's {@code spring.mail.*}, set through the environment.
 *
 * @param enabled    false logs the mails instead of sending them (local development, tests)
 * @param from       sender and reply-to address, a mailbox the owner reads
 * @param ownerEmail receives a copy of every paid order; blank disables the owner notification
 * @param shopName   display name in the sender and the subject
 * @param shopUrl    public origin of the shop, for links in the owner notification
 */
@ConfigurationProperties(prefix = "notifications")
public record NotificationProperties(boolean enabled, String from, String ownerEmail, String shopName, String shopUrl) {

    public boolean ownerNotified() {
        return ownerEmail != null && !ownerEmail.isBlank();
    }
}
