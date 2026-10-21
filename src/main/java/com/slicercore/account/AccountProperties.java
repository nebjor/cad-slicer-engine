package com.slicercore.account;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Customer account settings, bound from {@code account.*}.
 *
 * @param cookieSecure              send the session cookie with the Secure flag; true in
 *                                  production, false only for local HTTP development
 * @param loginLinkMinutes          how long a login link can be used after it was requested
 * @param sessionDays               how long a session lasts; extended on use, see
 *                                  {@link AccountSession#extend}
 * @param loginLinksPerEmail        links one address may request within {@code loginLinkMinutes}
 * @param loginLinksPerClientPerHour links one client address may request within an hour
 */
@ConfigurationProperties(prefix = "account")
public record AccountProperties(
        boolean cookieSecure,
        int loginLinkMinutes,
        int sessionDays,
        int loginLinksPerEmail,
        int loginLinksPerClientPerHour) {

    public Duration loginLinkValidity() {
        return Duration.ofMinutes(loginLinkMinutes);
    }

    public Duration sessionLength() {
        return Duration.ofDays(sessionDays);
    }
}
