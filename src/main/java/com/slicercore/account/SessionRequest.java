package com.slicercore.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The token from a login link, to be exchanged for a session. */
public record SessionRequest(@NotBlank @Size(max = 128) String token) {
}
