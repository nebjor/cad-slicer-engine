package com.slicercore.order;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Name, e-mail and shipping address from the checkout form. */
public record CustomerDetails(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 160) String street,
        @NotBlank @Size(max = 16) String postalCode,
        @NotBlank @Size(max = 120) String city,
        @NotBlank @Pattern(regexp = "[A-Za-z]{2}", message = "must be a two-letter country code") String country) {

    /** Trimmed, e-mail lower-cased, country upper-cased: the form as it is stored. */
    public Order.Customer normalized() {
        return new Order.Customer(name.trim(), email.trim().toLowerCase(), street.trim(), postalCode.trim(),
                city.trim(), country.trim().toUpperCase());
    }
}
