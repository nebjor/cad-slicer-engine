package com.slicercore.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A browser session behind the {@code pod_session} cookie. The cookie carries a random
 * secret; only its SHA-256 hash is stored. A session lasts a fixed time from its last
 * extension, so a customer who keeps coming back stays logged in and one who stops is
 * logged out on schedule.
 */
@Entity
@Table(name = "account_sessions")
public class AccountSession {

    @Id
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected AccountSession() {
        // JPA
    }

    public static AccountSession open(Account account, String tokenHash, Instant now, Duration length) {
        AccountSession session = new AccountSession();
        session.id = UUID.randomUUID();
        session.account = account;
        session.tokenHash = tokenHash;
        session.createdAt = now;
        session.lastSeenAt = now;
        session.expiresAt = now.plus(length);
        return session;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public void touch(Instant now) {
        lastSeenAt = now;
    }

    /**
     * Sliding expiry: pushes the end of the session to {@code now + length} when the last
     * extension is at least {@code refreshAfter} ago, and reports whether it did so the cookie
     * can be re-sent. Not on every request, so a busy customer causes no write per click.
     */
    public boolean extend(Instant now, Duration length, Duration refreshAfter) {
        Instant lastExtended = expiresAt.minus(length);
        if (Duration.between(lastExtended, now).compareTo(refreshAfter) < 0) {
            return false;
        }
        expiresAt = now.plus(length);
        return true;
    }

    public UUID getId() {
        return id;
    }

    public Account getAccount() {
        return account;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
