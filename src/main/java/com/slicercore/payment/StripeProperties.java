package com.slicercore.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stripe credentials, bound from {@code payments.stripe.*} and set only through the environment.
 *
 * @param secretKey     secret API key ({@code sk_test_...} / {@code sk_live_...})
 * @param webhookSecret signing secret of the webhook endpoint ({@code whsec_...})
 */
@ConfigurationProperties(prefix = "payments.stripe")
public record StripeProperties(String secretKey, String webhookSecret) {
}
