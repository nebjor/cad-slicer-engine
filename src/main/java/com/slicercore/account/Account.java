package com.slicercore.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A customer who has proven an e-mail address by opening a login link. The address is the
 * identity; a password is optional and only a second way in, the mail link always works. The
 * shipping details are a convenience for the checkout and stay empty until the customer saves
 * them or an earlier order supplies them.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(length = 120)
    private String name;

    @Column(length = 160)
    private String street;

    @Column(name = "postal_code", length = 16)
    private String postalCode;

    @Column(length = 120)
    private String city;

    @Column(length = 2)
    private String country;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_login_at", nullable = false)
    private Instant lastLoginAt;

    /** Bcrypt hash from {@link PasswordHasher}; null while the account is link-only. */
    @Column(name = "password_hash")
    private String passwordHash;

    protected Account() {
        // JPA
    }

    /** @param email already lower-cased and trimmed; the first login is now */
    public static Account create(String email, Instant now) {
        Account account = new Account();
        account.id = UUID.randomUUID();
        account.email = email;
        account.createdAt = now;
        account.lastLoginAt = now;
        return account;
    }

    public void recordLogin(Instant now) {
        lastLoginAt = now;
    }

    public void updateDetails(Details details) {
        name = details.name();
        street = details.street();
        postalCode = details.postalCode();
        city = details.city();
        country = details.country();
    }

    /** Sets or replaces the password; the previous hash is gone. */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /** Back to link-only. */
    public void clearPassword() {
        passwordHash = null;
    }

    public boolean hasPassword() {
        return passwordHash != null;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getName() {
        return name;
    }

    public String getStreet() {
        return street;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getCity() {
        return city;
    }

    public String getCountry() {
        return country;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    /** The stored hash, or null for a link-only account. */
    public String getPasswordHash() {
        return passwordHash;
    }

    /** Name and shipping address as the checkout needs them. */
    public record Details(String name, String street, String postalCode, String city, String country) {
    }
}
