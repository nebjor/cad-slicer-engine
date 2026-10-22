package com.slicercore.account;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Hashes account passwords with bcrypt through the application's {@link PasswordEncoder},
 * the same one the admin login uses. The password is pre-hashed with SHA-256 (hex) first so
 * that passwords of up to 128 characters fit bcrypt's 72-byte input instead of being refused.
 * A check against an account without a password still costs one bcrypt comparison, so the
 * time a login takes does not tell whether the address has one.
 */
@Component
public class PasswordHasher {

    private final PasswordEncoder encoder;
    private final String decoy;

    public PasswordHasher(PasswordEncoder encoder) {
        this.encoder = encoder;
        this.decoy = encoder.encode(Secrets.hash(Secrets.random()));
    }

    public String hash(String password) {
        return encoder.encode(Secrets.hash(password));
    }

    /** @param hash the stored hash, or null for an account without a password (never matches) */
    public boolean matches(String password, String hash) {
        if (hash == null) {
            encoder.matches(Secrets.hash(password), decoy);
            return false;
        }
        return encoder.matches(Secrets.hash(password), hash);
    }
}
