package com.slicercore.payment;

import com.stripe.StripeClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Stripe client when {@code payments.provider=stripe}. Both secrets are checked at
 * startup so a half-configured deployment fails to boot instead of failing at the first order.
 */
@Configuration
@ConditionalOnProperty(name = "payments.provider", havingValue = PaymentProperties.STRIPE)
class StripeConfig {

    @Bean
    CheckoutSessions checkoutSessions(StripeProperties properties) {
        require(properties.secretKey(), "STRIPE_SECRET_KEY");
        require(properties.webhookSecret(), "STRIPE_WEBHOOK_SECRET");
        StripeClient client = new StripeClient(properties.secretKey());
        return params -> client.checkout().sessions().create(params);
    }

    private static void require(String value, String variable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("payments.provider=stripe needs " + variable + " to be set.");
        }
    }
}
