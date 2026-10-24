package com.slicercore.account;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Customer account settings, bound from {@code account.*}.
 *
 * @param cookieSecure                  send the session cookie with the Secure flag; true in
 *                                      production, false only for local HTTP development
 * @param loginLinkMinutes              how long a login link can be used after it was requested
 * @param sessionDays                   how long a session lasts; extended on use, see
 *                                      {@link AccountSession#extend}
 * @param loginLinksPerEmail            links one address may request within {@code loginLinkMinutes}
 * @param loginLinksPerClientPerHour    links one client address may request within an hour
 * @param passwordLoginsPerEmail        password logins one address may attempt within fifteen
 *                                      minutes, see {@link PasswordLoginRateLimiter}
 * @param passwordLoginsPerClientPerHour password logins one client address may attempt within an hour
 * @param orderLookupsPerAccountPerMinute single-order lookups one account may make within a minute
 * @param orderLookupsPerClientPerMinute  single-order lookups one client address may make within a minute
 */
@ConfigurationProperties(prefix = "account")
public record AccountProperties(
        boolean cookieSecure,
        int loginLinkMinutes,
        int sessionDays,
        int loginLinksPerEmail,
        int loginLinksPerClientPerHour,
        int passwordLoginsPerEmail,
        int passwordLoginsPerClientPerHour,
        int orderLookupsPerAccountPerMinute,
        int orderLookupsPerClientPerMinute) {

    public Duration loginLinkValidity() {
        return Duration.ofMinutes(loginLinkMinutes);
    }

    public Duration sessionLength() {
        return Duration.ofDays(sessionDays);
    }
}
