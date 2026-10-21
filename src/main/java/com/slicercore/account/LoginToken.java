package com.slicercore.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One login link: a random secret the customer receives by mail, kept here only as its
 * SHA-256 hash. Valid for minutes and usable once; opening it proves the address.
 */
@Entity
@Table(name = "login_tokens")
public class LoginToken {

    private static final int CLIENT_KEY_LENGTH = 64;

    @Id
    private UUID id;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "client_key", nullable = false, length = CLIENT_KEY_LENGTH)
    private String clientKey;

    protected LoginToken() {
        // JPA
    }

    public static LoginToken issue(String email, String tokenHash, Instant now, Duration validity, String clientKey) {
        LoginToken token = new LoginToken();
        token.id = UUID.randomUUID();
        token.email = email;
        token.tokenHash = tokenHash;
        token.createdAt = now;
        token.expiresAt = now.plus(validity);
        token.clientKey = clientKey.length() <= CLIENT_KEY_LENGTH ? clientKey : clientKey.substring(0, CLIENT_KEY_LENGTH);
        return token;
    }

    /** Unused and not yet expired. */
    public boolean isUsable(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    public void markUsed(Instant now) {
        usedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getClientKey() {
        return clientKey;
    }
}
