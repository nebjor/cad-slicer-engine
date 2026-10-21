package com.slicercore.account;

import com.slicercore.order.RetentionSweep;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Joins the nightly retention run: expired login tokens and expired sessions are deleted.
 * Accounts themselves stay until the customer deletes them; they hold what the customer
 * chose to save and nothing that goes stale.
 */
@Component
public class AccountRetentionSweep implements RetentionSweep {

    private final LoginTokenRepository tokens;
    private final AccountSessionRepository sessions;
    private final Clock clock;

    public AccountRetentionSweep(LoginTokenRepository tokens, AccountSessionRepository sessions, Clock clock) {
        this.tokens = tokens;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    public String subject() {
        return "expired login tokens and sessions";
    }

    @Override
    @Transactional
    public int sweep() {
        Instant now = clock.instant();
        return tokens.deleteExpired(now) + sessions.deleteExpired(now);
    }
}
