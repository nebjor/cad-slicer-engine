package com.slicercore.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Name and shipping address to keep on the account. The same limits as the checkout's
 * customer details, so what is saved here can be sent to {@code POST /api/orders} unchanged.
 */
public record AccountUpdateRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 160) String street,
        @NotBlank @Size(max = 16) String postalCode,
        @NotBlank @Size(max = 120) String city,
        @NotBlank @Pattern(regexp = "[A-Za-z]{2}", message = "must be a two-letter country code") String country) {

    /** Trimmed, country upper-cased: the form as it is stored. */
    public Account.Details normalized() {
        return new Account.Details(name.trim(), street.trim(), postalCode.trim(), city.trim(), country.trim().toUpperCase());
    }
}
