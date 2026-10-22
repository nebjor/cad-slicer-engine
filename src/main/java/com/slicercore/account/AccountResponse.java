package com.slicercore.account;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * What the logged-in customer sees of their account; the address fields are null until saved.
 * {@code hasPassword} tells whether a password login is possible; the hash itself never leaves.
 */
public record AccountResponse(
        String email,
        @Schema(nullable = true) String name,
        @Schema(nullable = true) String street,
        @Schema(nullable = true) String postalCode,
        @Schema(nullable = true) String city,
        @Schema(nullable = true) String country,
        Instant createdAt,
        Instant lastLoginAt,
        boolean hasPassword) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getEmail(),
                account.getName(),
                account.getStreet(),
                account.getPostalCode(),
                account.getCity(),
                account.getCountry(),
                account.getCreatedAt(),
                account.getLastLoginAt(),
                account.hasPassword());
    }
}
