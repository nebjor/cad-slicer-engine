package com.slicercore.account;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The address a login link should go to. */
public record LoginLinkRequest(@NotBlank @Email @Size(max = 254) String email) {
}
