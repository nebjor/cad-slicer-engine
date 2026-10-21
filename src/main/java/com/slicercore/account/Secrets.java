package com.slicercore.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Login tokens and session secrets: 32 random bytes as base64url, so they survive a URL and
 * a cookie unencoded, and only their SHA-256 hash ever reaches the database.
 */
final class Secrets {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int BYTES = 32;

    private Secrets() {
    }

    static String random() {
        byte[] bytes = new byte[BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Lower-case hex SHA-256 of the secret, 64 characters. */
    static String hash(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime.", e);
        }
    }
}
