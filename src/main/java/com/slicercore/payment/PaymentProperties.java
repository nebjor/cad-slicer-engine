package com.slicercore.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Payment settings, bound from {@code payments.*}.
 *
 * @param provider      {@code mock} until the Stripe account is live, then {@code stripe}
 * @param publicBaseUrl origin of the shop as the customer sees it; redirect targets are built on it
 */
@ConfigurationProperties(prefix = "payments")
public record PaymentProperties(String provider, String publicBaseUrl) {

    public static final String MOCK = "mock";
    public static final String STRIPE = "stripe";
}
