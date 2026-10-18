package com.slicercore.order;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Checkout form, bound from the multipart fields next to the model file. The print parameters
 * repeat the quote request so the backend recomputes the price itself; {@code expectedTotalChf}
 * is what the customer saw and lets the backend refuse silently different prices.
 */
public record OrderRequest(
        @NotBlank String filament,
        String color,
        @NotBlank String quality,
        @Min(1) @Max(100) int quantity,
        BigDecimal expectedTotalChf,
        @NotBlank @Size(max = 120) String customerName,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 160) String street,
        @NotBlank @Size(max = 16) String postalCode,
        @NotBlank @Size(max = 120) String city,
        @NotBlank @Pattern(regexp = "[A-Za-z]{2}", message = "must be a two-letter country code") String country) {

    public Order.Customer customer() {
        return new Order.Customer(customerName.trim(), email.trim().toLowerCase(), street.trim(), postalCode.trim(),
                city.trim(), country.trim().toUpperCase());
    }
}
