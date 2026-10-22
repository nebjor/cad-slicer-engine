package com.slicercore.account;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A new password for the logged-in account; it is stored as typed, nothing is trimmed. */
public record PasswordRequest(
        @NotNull @Size(min = 10, max = 128, message = "must be 10 to 128 characters") String password) {
}
