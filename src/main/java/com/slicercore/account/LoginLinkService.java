package com.slicercore.account;

import com.slicercore.notification.Mailer;
import com.slicercore.notification.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

/**
 * Issues the registration mail: a one-time token behind a link that signs the customer in and
 * opens the password page. One mail serves registration, a forgotten password and an account
 * without one. The caller always gets the same answer whether or not a mail went out,
 * so the endpoint cannot be used to find out which addresses have an account; the reasons a
 * link was not sent (rate limit, mail failure) go to the log instead.
 */
@Service
public class LoginLinkService {

    private static final Logger log = LoggerFactory.getLogger(LoginLinkService.class);

    private final LoginTokenRepository tokens;
    private final LoginLinkRateLimiter limiter;
    private final Mailer mailer;
    private final NotificationProperties mailProps;
    private final AccountProperties props;
    private final Clock clock;

    public LoginLinkService(LoginTokenRepository tokens, LoginLinkRateLimiter limiter, Mailer mailer,
                            NotificationProperties mailProps, AccountProperties props, Clock clock) {
        this.tokens = tokens;
        this.limiter = limiter;
        this.mailer = mailer;
        this.mailProps = mailProps;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Stores a fresh one-time token for the address and mails the link, unless the address or
     * the client is over its quota.
     */
    @Transactional
    public void requestLink(String email, String clientKey) {
        String address = normalize(email);
        if (!limiter.allow(address, clientKey)) {
            log.info("Login link for {} requested by {} not sent: over the limit", address, clientKey);
            return;
        }
        Instant now = clock.instant();
        String secret = Secrets.random();
        tokens.save(LoginToken.issue(address, Secrets.hash(secret), now, props.loginLinkValidity(), clientKey));

        AccountMails.Mail mail = AccountMails.registration(address, secret, props.loginLinkMinutes(), mailProps);
        try {
            mailer.send(mail.to(), mail.subject(), mail.body());
            log.info("Registration mail sent to {}", address);
        } catch (RuntimeException e) {
            // The token stays and expires on its own; the customer simply receives nothing.
            log.error("Registration mail for {} could not be sent: {}", address, e.getMessage(), e);
        }
    }

    static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
